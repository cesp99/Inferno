// OpenCL loader + forwarders (see inferno_opencl.h). Each cl* symbol ggml-opencl calls is defined here and
// forwarded to the vendor driver through a pointer resolved with dlsym; a missing driver or symbol answers
// with a plain OpenCL error code, so the backend's own probing degrades to "no device" instead of a crash.
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS 1     // clCreateCommandQueue: ggml still uses the 1.2 entry point
#include "inferno_opencl.h"

#include <dlfcn.h>
#include <cstdlib>
#include <mutex>
#include <string>

#include <android/log.h>
#include <CL/cl.h>
#include <CL/cl_ext.h>     // CL_PLATFORM_NOT_FOUND_KHR

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "inferno-native", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "inferno-native", __VA_ARGS__)

namespace inferno {

namespace {

GpuPolicy   g_policy = GpuPolicy::AUTO;
void *      g_lib    = nullptr;
bool        g_tried  = false;
std::mutex  g_mutex;

// dlopen goes through the app's linker namespace: "libOpenCL.so" resolves only when the vendor lists it in
// /vendor/etc/public.libraries.txt AND the manifest declares it with <uses-native-library> (API 31+).
// The policy never gates this: the ggml registry probes devices once per process, so a driver refused under
// OFF would stay invisible after the user switches back to Auto. OFF/AUTO only decide opencl_use_gpu().
void * driver() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_tried) {
        return g_lib;
    }
    g_tried = true;
    g_lib = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_lib) {
        LOGI("opencl: no driver (%s)", dlerror());
    }
    return g_lib;
}

template <typename Fn>
Fn resolve(const char * name) {
    void * lib = driver();
    if (!lib) {
        return nullptr;
    }
    Fn fn = reinterpret_cast<Fn>(dlsym(lib, name));
    if (!fn) {
        LOGW("opencl: driver has no %s", name);
    }
    return fn;
}

} // namespace

void opencl_configure(GpuPolicy policy, const std::string & cache_dir) {
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_policy = policy;
    }
    if (!cache_dir.empty()) {
        // cl-program-cache.cpp: compiled kernels are reused across runs when this points at a writable dir
        // (its default is a temp dir Android app processes do not have).
        setenv("GGML_OPENCL_KERNEL_CACHE_DIR", cache_dir.c_str(), 1);
    }
}

const GpuProbe & opencl_probe() {
    static GpuProbe probe;
    static bool done = false;
    if (done) {
        return probe;
    }
    done = true;
    cl_uint n_platforms = 0;
    cl_platform_id platforms[8];
    if (clGetPlatformIDs(8, platforms, &n_platforms) != CL_SUCCESS || n_platforms == 0) {
        return probe;
    }
    for (cl_uint i = 0; i < n_platforms && !probe.available; i++) {
        cl_device_id dev;
        cl_uint n = 0;
        if (clGetDeviceIDs(platforms[i], CL_DEVICE_TYPE_GPU, 1, &dev, &n) != CL_SUCCESS || n == 0) {
            continue;
        }
        char buf[256];
        auto str = [&](cl_device_info what) {
            buf[0] = 0;
            clGetDeviceInfo(dev, what, sizeof(buf), buf, nullptr);
            buf[sizeof(buf) - 1] = 0;
            return std::string(buf);
        };
        probe.available = true;
        probe.name      = str(CL_DEVICE_NAME);
        probe.version   = str(CL_DEVICE_VERSION);
        probe.driver    = str(CL_DRIVER_VERSION);
        const std::string vendor = str(CL_DEVICE_VENDOR);
        probe.adreno = probe.name.find("Adreno") != std::string::npos || vendor.find("QUALCOMM") != std::string::npos;
    }
    if (probe.available) {
        LOGI("opencl: %s (%s, driver %s)%s", probe.name.c_str(), probe.version.c_str(), probe.driver.c_str(),
             probe.adreno ? " [adreno]" : "");
    }
    return probe;
}

GpuPolicy opencl_policy() {
    return g_policy;
}

bool opencl_use_gpu() {
    switch (g_policy) {
        case GpuPolicy::OFF:  return false;
        case GpuPolicy::ON:   return opencl_probe().available;
        case GpuPolicy::AUTO: return opencl_probe().adreno;
    }
    return false;
}

} // namespace inferno

// ---------------------------------------------------------------------------------------------- forwarders
// One per entry point ggml-opencl / cl-program-cache call (grep "\bcl[A-Z]" over those files when updating
// llama.cpp; an entry point missing here fails the link, never silently at runtime).

#define CL_FWD(ret, name, params, args, fail)                                                   \
    extern "C" CL_API_ENTRY ret CL_API_CALL name params {                                       \
        using Fn = ret (CL_API_CALL *) params;                                                  \
        static Fn fn = inferno::resolve<Fn>(#name);                                             \
        if (!fn) { fail; }                                                                      \
        return fn args;                                                                         \
    }
#define CL_ERR      return CL_INVALID_OPERATION
#define CL_NULL(ec) do { if (ec) *ec = CL_INVALID_OPERATION; return nullptr; } while (0)

CL_FWD(cl_int, clGetPlatformIDs, (cl_uint num_entries, cl_platform_id * platforms, cl_uint * num_platforms),
       (num_entries, platforms, num_platforms), { if (num_platforms) *num_platforms = 0; return CL_PLATFORM_NOT_FOUND_KHR; })
CL_FWD(cl_int, clGetPlatformInfo, (cl_platform_id platform, cl_platform_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret),
       (platform, param_name, param_value_size, param_value, param_value_size_ret), CL_ERR)
CL_FWD(cl_int, clGetDeviceIDs, (cl_platform_id platform, cl_device_type device_type, cl_uint num_entries, cl_device_id * devices, cl_uint * num_devices),
       (platform, device_type, num_entries, devices, num_devices), { if (num_devices) *num_devices = 0; return CL_DEVICE_NOT_FOUND; })
CL_FWD(cl_int, clGetDeviceInfo, (cl_device_id device, cl_device_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret),
       (device, param_name, param_value_size, param_value, param_value_size_ret), CL_ERR)
CL_FWD(cl_context, clCreateContext, (const cl_context_properties * properties, cl_uint num_devices, const cl_device_id * devices, void (CL_CALLBACK * pfn_notify)(const char *, const void *, size_t, void *), void * user_data, cl_int * errcode_ret),
       (properties, num_devices, devices, pfn_notify, user_data, errcode_ret), CL_NULL(errcode_ret))
CL_FWD(cl_command_queue, clCreateCommandQueue, (cl_context context, cl_device_id device, cl_command_queue_properties properties, cl_int * errcode_ret),
       (context, device, properties, errcode_ret), CL_NULL(errcode_ret))
CL_FWD(cl_mem, clCreateBuffer, (cl_context context, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret),
       (context, flags, size, host_ptr, errcode_ret), CL_NULL(errcode_ret))
CL_FWD(cl_mem, clCreateBufferWithProperties, (cl_context context, const cl_mem_properties * properties, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret),
       (context, properties, flags, size, host_ptr, errcode_ret), CL_NULL(errcode_ret))
CL_FWD(cl_mem, clCreateSubBuffer, (cl_mem buffer, cl_mem_flags flags, cl_buffer_create_type buffer_create_type, const void * buffer_create_info, cl_int * errcode_ret),
       (buffer, flags, buffer_create_type, buffer_create_info, errcode_ret), CL_NULL(errcode_ret))
CL_FWD(cl_mem, clCreateImage, (cl_context context, cl_mem_flags flags, const cl_image_format * image_format, const cl_image_desc * image_desc, void * host_ptr, cl_int * errcode_ret),
       (context, flags, image_format, image_desc, host_ptr, errcode_ret), CL_NULL(errcode_ret))
CL_FWD(cl_int, clReleaseMemObject, (cl_mem memobj), (memobj), CL_ERR)
CL_FWD(cl_program, clCreateProgramWithSource, (cl_context context, cl_uint count, const char ** strings, const size_t * lengths, cl_int * errcode_ret),
       (context, count, strings, lengths, errcode_ret), CL_NULL(errcode_ret))
CL_FWD(cl_program, clCreateProgramWithBinary, (cl_context context, cl_uint num_devices, const cl_device_id * device_list, const size_t * lengths, const unsigned char ** binaries, cl_int * binary_status, cl_int * errcode_ret),
       (context, num_devices, device_list, lengths, binaries, binary_status, errcode_ret), CL_NULL(errcode_ret))
CL_FWD(cl_int, clBuildProgram, (cl_program program, cl_uint num_devices, const cl_device_id * device_list, const char * options, void (CL_CALLBACK * pfn_notify)(cl_program, void *), void * user_data),
       (program, num_devices, device_list, options, pfn_notify, user_data), CL_ERR)
CL_FWD(cl_int, clGetProgramInfo, (cl_program program, cl_program_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret),
       (program, param_name, param_value_size, param_value, param_value_size_ret), CL_ERR)
CL_FWD(cl_int, clGetProgramBuildInfo, (cl_program program, cl_device_id device, cl_program_build_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret),
       (program, device, param_name, param_value_size, param_value, param_value_size_ret), CL_ERR)
CL_FWD(cl_int, clReleaseProgram, (cl_program program), (program), CL_ERR)
CL_FWD(cl_kernel, clCreateKernel, (cl_program program, const char * kernel_name, cl_int * errcode_ret),
       (program, kernel_name, errcode_ret), CL_NULL(errcode_ret))
CL_FWD(cl_int, clSetKernelArg, (cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void * arg_value),
       (kernel, arg_index, arg_size, arg_value), CL_ERR)
CL_FWD(cl_int, clGetKernelInfo, (cl_kernel kernel, cl_kernel_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret),
       (kernel, param_name, param_value_size, param_value, param_value_size_ret), CL_ERR)
CL_FWD(cl_int, clGetKernelWorkGroupInfo, (cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret),
       (kernel, device, param_name, param_value_size, param_value, param_value_size_ret), CL_ERR)
CL_FWD(cl_int, clGetKernelSubGroupInfo, (cl_kernel kernel, cl_device_id device, cl_kernel_sub_group_info param_name, size_t input_value_size, const void * input_value, size_t param_value_size, void * param_value, size_t * param_value_size_ret),
       (kernel, device, param_name, input_value_size, input_value, param_value_size, param_value, param_value_size_ret), CL_ERR)
CL_FWD(cl_int, clReleaseKernel, (cl_kernel kernel), (kernel), CL_ERR)
CL_FWD(cl_int, clEnqueueNDRangeKernel, (cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim, const size_t * global_work_offset, const size_t * global_work_size, const size_t * local_work_size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event),
       (command_queue, kernel, work_dim, global_work_offset, global_work_size, local_work_size, num_events_in_wait_list, event_wait_list, event), CL_ERR)
CL_FWD(cl_int, clEnqueueReadBuffer, (cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read, size_t offset, size_t size, void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event),
       (command_queue, buffer, blocking_read, offset, size, ptr, num_events_in_wait_list, event_wait_list, event), CL_ERR)
CL_FWD(cl_int, clEnqueueWriteBuffer, (cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write, size_t offset, size_t size, const void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event),
       (command_queue, buffer, blocking_write, offset, size, ptr, num_events_in_wait_list, event_wait_list, event), CL_ERR)
CL_FWD(cl_int, clEnqueueCopyBuffer, (cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer, size_t src_offset, size_t dst_offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event),
       (command_queue, src_buffer, dst_buffer, src_offset, dst_offset, size, num_events_in_wait_list, event_wait_list, event), CL_ERR)
CL_FWD(cl_int, clEnqueueFillBuffer, (cl_command_queue command_queue, cl_mem buffer, const void * pattern, size_t pattern_size, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event),
       (command_queue, buffer, pattern, pattern_size, offset, size, num_events_in_wait_list, event_wait_list, event), CL_ERR)
CL_FWD(void *, clEnqueueMapBuffer, (cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_map, cl_map_flags map_flags, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event, cl_int * errcode_ret),
       (command_queue, buffer, blocking_map, map_flags, offset, size, num_events_in_wait_list, event_wait_list, event, errcode_ret), CL_NULL(errcode_ret))
CL_FWD(cl_int, clEnqueueUnmapMemObject, (cl_command_queue command_queue, cl_mem memobj, void * mapped_ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event),
       (command_queue, memobj, mapped_ptr, num_events_in_wait_list, event_wait_list, event), CL_ERR)
CL_FWD(cl_int, clEnqueueMarkerWithWaitList, (cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event),
       (command_queue, num_events_in_wait_list, event_wait_list, event), CL_ERR)
CL_FWD(cl_int, clEnqueueBarrierWithWaitList, (cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event),
       (command_queue, num_events_in_wait_list, event_wait_list, event), CL_ERR)
CL_FWD(cl_int, clWaitForEvents, (cl_uint num_events, const cl_event * event_list), (num_events, event_list), CL_ERR)
CL_FWD(cl_int, clGetEventProfilingInfo, (cl_event event, cl_profiling_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret),
       (event, param_name, param_value_size, param_value, param_value_size_ret), CL_ERR)
CL_FWD(cl_int, clReleaseEvent, (cl_event event), (event), CL_ERR)
CL_FWD(cl_int, clFlush, (cl_command_queue command_queue), (command_queue), CL_ERR)
CL_FWD(cl_int, clFinish, (cl_command_queue command_queue), (command_queue), CL_ERR)
