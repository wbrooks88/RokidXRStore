// OpenXR runtime bridge for the Rokid Station 2.
//
// Rokid's runtime APK names libopenxr_monado.so, but ships libopenxr_rokid.so,
// so the loader can't find it. This APK carries a copy of that library plus
// Rokid's classes.dex, because Monado looks for its Java classes in the
// base.apk next to wherever the library was loaded from.
//
// The copy is opened with RTLD_NODELETE: the loader dlcloses the runtime between
// xrEnumerateInstanceExtensionProperties and xrCreateInstance, and the Rokid
// library's destructors abort ("JNI not initialized") when it is unloaded.
//
// It also wraps xrGetInstanceProcAddr to fill in swapchain create info that apps
// leave zeroed for XR_KHR_android_surface_swapchain (as that extension's spec says),
// which Rokid's runtime rejects with XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED, and to
// hide XR_KHR_composition_layer_cube: the runtime advertises it but can't create the
// compressed (ETC2) cube swapchains apps use for skyboxes, so Wolvic aborted.
//
// Input: the Station's touchpad/IMU controller only answers to
// /interaction_profiles/rokid/gamepad_controller, which no app outside Rokid knows. When an
// app suggests bindings for /interaction_profiles/khr/simple_controller, the same actions
// are also bound to the Rokid gamepad as one hand (right by default, since most apps treat
// it as primary). The runtime moves those bindings to the /user/gamepad top-level path, so
// /user/gamepad is added to that hand's actions, queries for that hand (action state, action
// spaces, current interaction profile) are redirected to /user/gamepad, and the gamepad is
// reported as a simple controller.
#include <dlfcn.h>
#include <limits.h>
#include <stdint.h>
#include <string.h>
#include <android/log.h>
#include <math.h>
#include <stdlib.h>
#include <sys/system_properties.h>

#define TAG "XRStoreBridge"
#define XR_ERROR_RUNTIME_FAILURE (-2)
#define GL_SRGB8_ALPHA8 0x8C43
#define XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT 0x1
#define XR_SWAPCHAIN_USAGE_SAMPLED_BIT 0x20

typedef int (*negotiate_fn)(const void *loaderInfo, void *runtimeRequest);
typedef void (*void_fn)(void);
typedef int (*gipa_fn)(void *instance, const char *name, void_fn *function);

// Layouts from openxr.h / openxr_loader_negotiation.h.
struct runtime_request {
	uint32_t structType, structVersion;
	size_t structSize;
	uint32_t runtimeInterfaceVersion;
	uint64_t runtimeApiVersion;
	gipa_fn getInstanceProcAddr;
};

struct swapchain_create_info {
	uint32_t type;
	const void *next;
	uint64_t createFlags, usageFlags;
	int64_t format;
	uint32_t sampleCount, width, height, faceCount, arraySize, mipCount;
};

typedef int (*create_surface_fn)(void *session, const struct swapchain_create_info *info,
                                 void **swapchain, void **surface);

typedef int (*create_swapchain_fn)(void *session, const struct swapchain_create_info *info, void **swapchain);

struct extension_properties {
	uint32_t type;
	void *next;
	char extensionName[128];
	uint32_t extensionVersion;
};

typedef int (*enumerate_extensions_fn)(const char *layer, uint32_t capacity, uint32_t *count,
                                       struct extension_properties *properties);

// Advertised by Rokid's runtime but not usable: cube swapchains need formats it lacks, and
// android surface swapchains come back without a Surface.
static const char *const hidden_extensions[] = {
	"XR_KHR_composition_layer_cube",
	"XR_KHR_android_surface_swapchain",
};

static enumerate_extensions_fn real_enumerate_extensions;

static int is_hidden(const char *name)
{
	for (size_t i = 0; i < sizeof(hidden_extensions) / sizeof(*hidden_extensions); i++)
		if (strcmp(name, hidden_extensions[i]) == 0)
			return 1;
	return 0;
}

static int enumerate_extensions(const char *layer, uint32_t capacity, uint32_t *count,
                                struct extension_properties *properties)
{
	uint32_t total;
	int result = real_enumerate_extensions(layer, 0, &total, NULL);
	if (result)
		return result;
	struct extension_properties all[total ? total : 1];
	for (uint32_t i = 0; i < total; i++)
		all[i] = (struct extension_properties){.type = 2}; // XR_TYPE_EXTENSION_PROPERTIES
	result = real_enumerate_extensions(layer, total, &total, all);
	if (result)
		return result;
	uint32_t kept = 0;
	for (uint32_t i = 0; i < total; i++) {
		if (is_hidden(all[i].extensionName))
			continue;
		if (capacity) {
			if (kept >= capacity)
				return -11; // XR_ERROR_SIZE_INSUFFICIENT
			void *next = properties[kept].next;
			properties[kept] = all[i];
			properties[kept].next = next;
		}
		kept++;
	}
	*count = kept;
	if (total != kept)
		__android_log_print(ANDROID_LOG_INFO, TAG, "hid %u extension(s)", total - kept);
	return 0;
}

typedef uint64_t xr_path;

struct suggested_binding {
	uint64_t action;
	xr_path binding;
};

struct profile_suggestion {
	uint32_t type;
	const void *next;
	xr_path interactionProfile;
	uint32_t countSuggestedBindings;
	const struct suggested_binding *suggestedBindings;
};

struct profile_state {
	uint32_t type;
	void *next;
	xr_path interactionProfile;
};

typedef int (*string_to_path_fn)(void *instance, const char *string, xr_path *path);
typedef int (*path_to_string_fn)(void *instance, xr_path path, uint32_t capacity, uint32_t *count, char *buffer);
typedef int (*suggest_fn)(void *instance, const struct profile_suggestion *suggestion);
typedef int (*current_profile_fn)(void *session, xr_path user, struct profile_state *state);

static gipa_fn real_gipa;
static suggest_fn real_suggest;
static current_profile_fn real_current_profile;
static string_to_path_fn string_to_path;
static path_to_string_fn path_to_string;
/* The hand the Station acts as (debug.xrstore.hand = left|right, default right). */
static xr_path simple_profile, rokid_profile, station_hand, gamepad;
static char station_hand_prefix[24] = "/user/hand/right/";

#define SIMPLE_PROFILE "/interaction_profiles/khr/simple_controller"
#define ROKID_PROFILE "/interaction_profiles/rokid/gamepad_controller"

/*
 * Controller profiles the Station stands in for, and how their inputs (after
 * /user/hand/<side>) map onto the Rokid gamepad. The Rokid profile only defines left-hand
 * paths, whichever hand the app's actions are for. Bindings from every listed profile the
 * app suggests are combined onto the Rokid profile, so an action an app only binds for one
 * controller type (Godot's trigger, for example) still works.
 */
#define TOUCH_PROFILE "/interaction_profiles/oculus/touch_controller"
enum { EMU_SIMPLE, EMU_TOUCH, EMU_COUNT };
static const char *const emulated_profiles[EMU_COUNT] = {SIMPLE_PROFILE, TOUCH_PROFILE};
static xr_path emulated_paths[EMU_COUNT];
static const char *const input_map[][3] = { // profile index, app input, Rokid input
	{"0", "/input/select/click", "/user/hand/left/input/select/click"},
	{"0", "/input/menu/click", "/user/hand/left/input/menu/click"},
	{"0", "/input/aim/pose", "/user/hand/left/input/aim/pose"},
	{"0", "/input/grip/pose", "/user/hand/left/input/aim/pose"},
	{"1", "/input/trigger/value", "/user/hand/left/input/select/click"},
	{"1", "/input/trigger/touch", "/user/hand/left/input/select/click"},
	{"1", "/input/b/click", "/user/hand/left/input/menu/click"},
	{"1", "/input/menu/click", "/user/hand/left/input/menu/click"},
	{"1", "/input/aim/pose", "/user/hand/left/input/aim/pose"},
	{"1", "/input/grip/pose", "/user/hand/left/input/aim/pose"},
};

#define MAX_EMU_BINDINGS 64
static struct suggested_binding emu_bindings[EMU_COUNT][MAX_EMU_BINDINGS];
static uint32_t emu_count[EMU_COUNT];
static int emu_seen[EMU_COUNT];

static void resolve_paths(void *instance);

/* setprop debug.xrstore.verbose 1 logs bindings and actions. */
static int verbose(void)
{
	char value[PROP_VALUE_MAX] = "";
	__system_property_get("debug.xrstore.verbose", value);
	return value[0] == '1';
}

static int suggest(void *instance, const struct profile_suggestion *suggestion)
{
	int result = real_suggest(instance, suggestion);
	resolve_paths(instance);
	int emu = 0;
	while (emu < EMU_COUNT && (!string_to_path || suggestion->interactionProfile != emulated_paths[emu]))
		emu++;
	if (emu == EMU_COUNT)
		return result;

	// This profile's bindings, translated (a repeated suggestion replaces them).
	uint32_t n = 0;
	for (uint32_t i = 0; i < suggestion->countSuggestedBindings && n < MAX_EMU_BINDINGS; i++) {
		char path[256];
		uint32_t len;
		if (path_to_string(instance, suggestion->suggestedBindings[i].binding, sizeof(path), &len, path))
			continue;
		if (verbose())
			__android_log_print(ANDROID_LOG_INFO, TAG, "%s binding %s (action %llx)",
			                    emulated_profiles[emu], path,
			                    (unsigned long long)suggestion->suggestedBindings[i].action);
		const char *input = strstr(path, "/input/");
		if (strncmp(path, station_hand_prefix, strlen(station_hand_prefix)) != 0 || !input)
			continue; // the Rokid gamepad is one hand
		for (size_t m = 0; m < sizeof(input_map) / sizeof(*input_map); m++) {
			xr_path target;
			if (input_map[m][0][0] - '0' != emu || strcmp(input, input_map[m][1]) != 0
			    || string_to_path(instance, input_map[m][2], &target) != 0)
				continue;
			emu_bindings[emu][n++] = (struct suggested_binding){suggestion->suggestedBindings[i].action, target};
		}
	}
	emu_count[emu] = n;
	emu_seen[emu] = 1;

	// All translated bindings, without duplicates, become the Rokid profile's suggestion.
	struct suggested_binding all[EMU_COUNT * MAX_EMU_BINDINGS];
	uint32_t total = 0;
	for (int e = 0; e < EMU_COUNT; e++) {
		for (uint32_t i = 0; i < emu_count[e]; i++) {
			uint32_t j = 0;
			while (j < total && (all[j].action != emu_bindings[e][i].action || all[j].binding != emu_bindings[e][i].binding))
				j++;
			if (j == total)
				all[total++] = emu_bindings[e][i];
		}
	}
	if (total) {
		struct profile_suggestion extra = *suggestion;
		extra.interactionProfile = rokid_profile;
		extra.countSuggestedBindings = total;
		extra.suggestedBindings = all;
		int r = real_suggest(instance, &extra);
		__android_log_print(r ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, TAG,
		                    "bound %u inputs to the Rokid gamepad -> %d", total, r);
	}
	return result;
}

/* Resolves the paths the bridge needs once the instance is known. */
static void resolve_paths(void *instance)
{
	if (string_to_path || !real_gipa)
		return;
	void_fn f;
	if (real_gipa(instance, "xrStringToPath", &f) == 0)
		string_to_path = (string_to_path_fn)f;
	if (real_gipa(instance, "xrPathToString", &f) == 0)
		path_to_string = (path_to_string_fn)f;
	if (!string_to_path || !path_to_string) {
		string_to_path = NULL;
		return;
	}
	string_to_path(instance, SIMPLE_PROFILE, &simple_profile);
	string_to_path(instance, ROKID_PROFILE, &rokid_profile);
	for (int e = 0; e < EMU_COUNT; e++)
		string_to_path(instance, emulated_profiles[e], &emulated_paths[e]);
	char hand[PROP_VALUE_MAX] = "";
	__system_property_get("debug.xrstore.hand", hand);
	if (strcmp(hand, "left") == 0)
		strcpy(station_hand_prefix, "/user/hand/left/");
	char user[24];
	strcpy(user, station_hand_prefix);
	user[strlen(user) - 1] = 0;
	string_to_path(instance, user, &station_hand);
	string_to_path(instance, "/user/gamepad", &gamepad);
}

static string_to_path_fn real_string_to_path;

static int app_string_to_path(void *instance, const char *string, xr_path *path)
{
	resolve_paths(instance);
	return real_string_to_path(instance, string, path);
}

/* XrActionCreateInfo */
struct action_create_info {
	uint32_t type;
	const void *next;
	char actionName[64];
	uint32_t actionType;
	uint32_t countSubactionPaths;
	const xr_path *subactionPaths;
	char localizedActionName[128];
};

typedef int (*create_action_fn)(void *action_set, const struct action_create_info *info, void **action);
static create_action_fn real_create_action;

/*
 * The runtime adds /user/gamepad to some of an app's boolean actions, but not to pose
 * actions, so the Station's aim pose never reached them. Add it to every action that has
 * the Station's hand as a subaction path.
 */
static int create_action(void *action_set, const struct action_create_info *info, void **action)
{
	if (verbose())
		__android_log_print(ANDROID_LOG_INFO, TAG, "action %s type %u, %u subaction paths",
		                    info->actionName, info->actionType, info->countSubactionPaths);
	int has_left = 0, has_gamepad = 0;
	for (uint32_t i = 0; gamepad && i < info->countSubactionPaths; i++) {
		has_left |= info->subactionPaths[i] == station_hand;
		has_gamepad |= info->subactionPaths[i] == gamepad;
	}
	if (has_left && !has_gamepad && info->countSubactionPaths < 16) {
		xr_path paths[17];
		memcpy(paths, info->subactionPaths, info->countSubactionPaths * sizeof(xr_path));
		paths[info->countSubactionPaths] = gamepad;
		struct action_create_info extended = *info;
		extended.countSubactionPaths++;
		extended.subactionPaths = paths;
		int result = real_create_action(action_set, &extended, action);
		if (result == 0)
			return 0;
		__android_log_print(ANDROID_LOG_WARN, TAG, "action %s with /user/gamepad -> %d",
		                    info->actionName, result);
	}
	return real_create_action(action_set, info, action);
}

static int current_profile(void *session, xr_path user, struct profile_state *state)
{
	int result = real_current_profile(session, user, state);
	if (result == 0 && gamepad && user == station_hand && !state->interactionProfile)
		result = real_current_profile(session, gamepad, state);
	if (result == 0 && rokid_profile && state->interactionProfile == rokid_profile) {
		// Report the first emulated profile the app knows (simple controller first).
		state->interactionProfile = simple_profile;
		for (int e = 0; e < EMU_COUNT; e++) {
			if (emu_seen[e]) {
				state->interactionProfile = emulated_paths[e];
				break;
			}
		}
	}
	return result;
}

/* XrActionStateGetInfo and XrActionSpaceCreateInfo both start type, next, action, subactionPath. */
struct action_info {
	uint32_t type;
	const void *next;
	uint64_t action;
	xr_path subactionPath;
};

typedef int (*action_query_fn)(void *handle, const struct action_info *info, void *out);

enum { GET_BOOLEAN, GET_FLOAT, GET_VECTOR2F, GET_POSE, CREATE_SPACE, ACTION_QUERIES };
static const char *const action_query_names[ACTION_QUERIES] = {
	"xrGetActionStateBoolean", "xrGetActionStateFloat", "xrGetActionStateVector2f",
	"xrGetActionStatePose", "xrCreateActionSpace",
};
static action_query_fn real_action_query[ACTION_QUERIES];

static int action_query(int which, void *handle, const struct action_info *info, void *out)
{
	if (!gamepad || info->subactionPath != station_hand)
		return real_action_query[which](handle, info, out);
	size_t size = which == CREATE_SPACE ? 64 : sizeof(*info); // XrActionSpaceCreateInfo is 64 bytes
	unsigned char copy[64];
	memcpy(copy, info, size);
	((struct action_info *)copy)->subactionPath = gamepad;
	int result = real_action_query[which](handle, (const struct action_info *)copy, out);
	// The runtime only adds /user/gamepad to some actions (not pose actions); for the rest,
	// ask about the left hand as the app did.
	if (result)
		result = real_action_query[which](handle, info, out);
	if (which == GET_BOOLEAN && result == 0) {
		// XrActionStateBoolean: type, next, currentState, changedSinceLastSync
		uint32_t *state = out;
		if (state[4] && state[5])
			__android_log_print(ANDROID_LOG_INFO, TAG, "app sees button pressed (action %llx)",
			                    (unsigned long long)info->action);
	}
	if (which == GET_POSE) {
		static unsigned calls;
		if (calls++ % 600 == 0) // XrActionStatePose: type, next, isActive
			__android_log_print(ANDROID_LOG_INFO, TAG, "aim pose active=%u (result %d)",
			                    ((uint32_t *)out)[4], result);
	}
	return result;
}

/*
 * Optional aim offset for testing without moving the Station: setprop debug.xrstore.aim_pitch
 * and debug.xrstore.aim_yaw (degrees) tilt every action space's orientation. Off when unset.
 */
#define MAX_ACTION_SPACES 32
static uint64_t action_spaces[MAX_ACTION_SPACES];
static unsigned action_space_count;

typedef int (*locate_space_fn)(uint64_t space, uint64_t base, int64_t time, void *location);
static locate_space_fn real_locate_space;

static float prop_degrees(const char *name)
{
	char value[PROP_VALUE_MAX] = "";
	__system_property_get(name, value);
	return value[0] ? strtof(value, NULL) * (float)M_PI / 180.0f : 0.0f;
}

static void quat_mul(const float *a, const float *b, float *out) // xyzw
{
	out[0] = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1];
	out[1] = a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0];
	out[2] = a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3];
	out[3] = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2];
}

static int locate_space(uint64_t space, uint64_t base, int64_t time, void *location)
{
	int result = real_locate_space(space, base, time, location);
	if (result)
		return result;
	unsigned i = 0;
	while (i < action_space_count && action_spaces[i] != space)
		i++;
	if (i == action_space_count)
		return result;
	float pitch = prop_degrees("debug.xrstore.aim_pitch"), yaw = prop_degrees("debug.xrstore.aim_yaw");
	if (pitch == 0.0f && yaw == 0.0f)
		return result;
	// XrSpaceLocation: type, next, locationFlags, pose { orientation xyzw, position }
	float *q = (float *)((char *)location + 24), tmp[4], out[4];
	float y[4] = {0, sinf(yaw / 2), 0, cosf(yaw / 2)}, x[4] = {sinf(pitch / 2), 0, 0, cosf(pitch / 2)};
	quat_mul(q, y, tmp);
	quat_mul(tmp, x, out);
	memcpy(q, out, sizeof(out));
	return result;
}

static int get_boolean(void *s, const struct action_info *i, void *o) { return action_query(GET_BOOLEAN, s, i, o); }
static int get_float(void *s, const struct action_info *i, void *o) { return action_query(GET_FLOAT, s, i, o); }
static int get_vector2f(void *s, const struct action_info *i, void *o) { return action_query(GET_VECTOR2F, s, i, o); }
static int get_pose(void *s, const struct action_info *i, void *o) { return action_query(GET_POSE, s, i, o); }
static int create_space(void *s, const struct action_info *i, void *o)
{
	int result = action_query(CREATE_SPACE, s, i, o);
	if (result == 0 && action_space_count < MAX_ACTION_SPACES)
		action_spaces[action_space_count++] = *(uint64_t *)o;
	return result;
}
static const action_query_fn action_query_wrappers[ACTION_QUERIES] = {
	get_boolean, get_float, get_vector2f, get_pose, create_space,
};

static create_surface_fn real_create_surface;
static create_swapchain_fn real_create_swapchain;

#define XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED (-26)
#define GL_COMPRESSED_RGB8_ETC2 0x9274
#define GL_COMPRESSED_SRGB8_ALPHA8_ETC2_EAC 0x9279

static int create_swapchain(void *session, const struct swapchain_create_info *info, void **swapchain)
{
	int result = real_create_swapchain(session, info, swapchain);
	if (result == XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED && info->format >= GL_COMPRESSED_RGB8_ETC2
	    && info->format <= GL_COMPRESSED_SRGB8_ALPHA8_ETC2_EAC) {
		// Compressed (ETC2) images, e.g. Wolvic's skybox: the runtime has no compressed
		// formats, so fall back to plain RGBA. The app's compressed upload then fails
		// and that layer stays blank, which beats the app aborting.
		struct swapchain_create_info plain = *info;
		plain.format = GL_SRGB8_ALPHA8;
		result = real_create_swapchain(session, &plain, swapchain);
	}
	if (result)
		__android_log_print(ANDROID_LOG_WARN, TAG,
		                    "swapchain %ux%u format 0x%llx faces %u array %u mips %u samples %u usage 0x%llx flags 0x%llx -> %d",
		                    info->width, info->height, (long long)info->format, info->faceCount,
		                    info->arraySize, info->mipCount, info->sampleCount,
		                    (long long)info->usageFlags, (long long)info->createFlags, result);
	return result;
}

static int create_surface(void *session, const struct swapchain_create_info *info,
                          void **swapchain, void **surface)
{
	struct swapchain_create_info fixed = *info;
	if (fixed.format == 0) {
		fixed.format = GL_SRGB8_ALPHA8;
		fixed.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
		fixed.sampleCount = fixed.faceCount = fixed.arraySize = fixed.mipCount = 1;
	}
	int result = real_create_surface(session, &fixed, swapchain, surface);
	__android_log_print(result ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, TAG,
	                    "android surface swapchain %ux%u format 0x%llx -> %d",
	                    fixed.width, fixed.height, (long long)fixed.format, result);
	return result;
}

static int gipa(void *instance, const char *name, void_fn *function)
{
	int result = real_gipa(instance, name, function);
	if (result == 0 && *function && strcmp(name, "xrCreateSwapchainAndroidSurfaceKHR") == 0) {
		real_create_surface = (create_surface_fn)*function;
		*function = (void_fn)create_surface;
	} else if (result == 0 && *function && strcmp(name, "xrEnumerateInstanceExtensionProperties") == 0) {
		real_enumerate_extensions = (enumerate_extensions_fn)*function;
		*function = (void_fn)enumerate_extensions;
	} else if (result == 0 && *function && strcmp(name, "xrLocateSpace") == 0) {
		real_locate_space = (locate_space_fn)*function;
		*function = (void_fn)locate_space;
	} else if (result == 0 && *function && strcmp(name, "xrStringToPath") == 0) {
		real_string_to_path = (string_to_path_fn)*function;
		*function = (void_fn)app_string_to_path;
	} else if (result == 0 && *function && strcmp(name, "xrCreateAction") == 0) {
		real_create_action = (create_action_fn)*function;
		*function = (void_fn)create_action;
	} else if (result == 0 && *function && strcmp(name, "xrSuggestInteractionProfileBindings") == 0) {
		real_suggest = (suggest_fn)*function;
		*function = (void_fn)suggest;
	} else if (result == 0 && *function && strcmp(name, "xrGetCurrentInteractionProfile") == 0) {
		real_current_profile = (current_profile_fn)*function;
		*function = (void_fn)current_profile;
	} else if (result == 0 && *function && strncmp(name, "xr", 2) == 0
	           && (strstr(name, "ActionState") || strcmp(name, "xrCreateActionSpace") == 0)) {
		for (int i = 0; i < ACTION_QUERIES; i++) {
			if (strcmp(name, action_query_names[i]) == 0) {
				real_action_query[i] = (action_query_fn)*function;
				*function = (void_fn)action_query_wrappers[i];
			}
		}
	} else if (result == 0 && *function && strcmp(name, "xrCreateSwapchain") == 0) {
		real_create_swapchain = (create_swapchain_fn)*function;
		*function = (void_fn)create_swapchain;
	}
	return result;
}

__attribute__((visibility("default")))
int xrNegotiateLoaderRuntimeInterface(const void *loaderInfo, void *runtimeRequest)
{
	static negotiate_fn real;
	if (!real) {
		Dl_info self;
		char path[PATH_MAX];
		if (!dladdr((void *)xrNegotiateLoaderRuntimeInterface, &self) || !self.dli_fname) {
			__android_log_print(ANDROID_LOG_ERROR, TAG, "dladdr failed");
			return XR_ERROR_RUNTIME_FAILURE;
		}
		strlcpy(path, self.dli_fname, sizeof(path));
		char *slash = strrchr(path, '/');
		if (!slash)
			return XR_ERROR_RUNTIME_FAILURE;
		strlcpy(slash + 1, "libopenxr_rokid.so", sizeof(path) - (slash + 1 - path));

		void *lib = dlopen(path, RTLD_NOW | RTLD_NODELETE);
		if (!lib) {
			__android_log_print(ANDROID_LOG_ERROR, TAG, "dlopen %s failed: %s", path, dlerror());
			return XR_ERROR_RUNTIME_FAILURE;
		}
		real = (negotiate_fn)dlsym(lib, "xrNegotiateLoaderRuntimeInterface");
		if (!real) {
			__android_log_print(ANDROID_LOG_ERROR, TAG, "dlsym failed: %s", dlerror());
			return XR_ERROR_RUNTIME_FAILURE;
		}
		__android_log_print(ANDROID_LOG_INFO, TAG, "forwarding to %s", path);
	}
	int result = real(loaderInfo, runtimeRequest);
	if (result == 0) {
		struct runtime_request *request = runtimeRequest;
		real_gipa = request->getInstanceProcAddr;
		request->getInstanceProcAddr = gipa;
	}
	return result;
}
