cmake_minimum_required(VERSION 3.22.1)
project(pptp_native C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

# Helper is a position-independent executable shipped as lib<name>.so so the
# Android installer extracts it to applicationInfo.nativeLibraryDir. The PIE
# linker flags below produce a standalone binary the Android linker will not
# try to dlopen() — invocation is via execve from libsu's su shell.
add_executable(pptp_helper
        helper_main.c
)

set_target_properties(pptp_helper PROPERTIES
        # Force the Android installer's lib*.so extraction path.
        OUTPUT_NAME "pptp_helper"
        PREFIX "lib"
        SUFFIX ".so"
)

target_compile_options(pptp_helper PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -Werror=implicit-function-declaration
        -Werror=return-type
        -fPIE
        -fstack-protector-strong
)

target_link_options(pptp_helper PRIVATE
        -pie
        -Wl,-z,relro
        -Wl,-z,now
        -Wl,--no-undefined
        # Android 15+ devices may run with 16 KB memory pages. Force LOAD
        # segment alignment to 16 KB so the helper .so loads on those devices
        # (NDK r26c+ does this by default for API 35; we set it explicitly to
        # remain robust across NDK versions and avoid the "lib not compatible
        # with 16 KB devices" packaging warning).
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
)
