cmake_minimum_required(VERSION 3.10.2)
project(wbeam)

add_library(wbeam SHARED
        native_wbeam.cpp)

find_library(log-lib log)
find_library(android-lib android)

# REQUIRE: link against libjpeg-turbo.
# You have two options:
#  1) bundle prebuilt libturbojpeg.so + headers (recommended for API17 ARMv7),
#  2) add libjpeg-turbo as source and build it (heavier).
#
# Example prebuilt layout:
#   app/src/main/cpp/include/turbojpeg.h
#   app/src/main/jniLibs/armeabi-v7a/libturbojpeg.so
#
# Then:
set(WBEAM_CPP_DIR ${CMAKE_CURRENT_LIST_DIR})
set(TURBOJPEG_HEADER ${WBEAM_CPP_DIR}/include/turbojpeg.h)
set(TURBOJPEG_SO ${WBEAM_CPP_DIR}/../jniLibs/${ANDROID_ABI}/libturbojpeg.so)

if(EXISTS ${TURBOJPEG_HEADER} AND EXISTS ${TURBOJPEG_SO})
    target_compile_definitions(wbeam PRIVATE WBEAM_USE_TURBOJPEG=1)
    target_include_directories(wbeam PRIVATE
                        ${WBEAM_CPP_DIR}/include)

    add_library(turbojpeg SHARED IMPORTED)
    set_target_properties(turbojpeg PROPERTIES
            IMPORTED_LOCATION ${TURBOJPEG_SO})

    target_link_libraries(wbeam
            turbojpeg
            ${android-lib}
            ${log-lib})
else()
    message(WARNING "libturbojpeg for ${ANDROID_ABI} not found; building wbeam native stub")
    target_compile_definitions(wbeam PRIVATE WBEAM_USE_TURBOJPEG=0)
    target_link_libraries(wbeam
            ${android-lib}
            ${log-lib})
endif()
