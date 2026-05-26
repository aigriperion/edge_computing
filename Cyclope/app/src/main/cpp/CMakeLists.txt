# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("cyclope")

# Stubs OpenCV header-only (pas de lib externe requise)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/opencv_stub)
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
    # List C/C++ source files with relative paths to this CMakeLists.txt.
    native-lib.cpp
    CV_Manager.cpp
    Image_Reader.cpp
    Native_Camera.cpp
    )

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
    # List libraries link to the target library
    # OpenCV remplacé par stubs header-only
    camera2ndk      # Bibliothèque NDK pour la caméra.
    mediandk        # Bibliothèque NDK pour le multimédia.
    android
    log)