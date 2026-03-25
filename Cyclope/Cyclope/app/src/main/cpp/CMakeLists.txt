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

set(OpenCV_DIR C:/Users/ladiaviakoye/Documents/MesCours/Android_NDK/Opencv/sdk/native/jni)

# Recherche la bibliothèque système 'log' (nécessaire pour la journalisation Android).
#find_library(log-lib log)

# Recherche la bibliothèque OpenCV (souvent nommée opencv_java4) pour l'ABI en cours.
find_package(OpenCV REQUIRED)
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
    ${log-lib}      # Bibliothèque de journalisation Android.
    ${OpenCV_LIBS}   # Bibliothèque OpenCV.
    camera2ndk      # Bibliothèque NDK pour la caméra.
    mediandk        # Bibliothèque NDK pour le multimédia.
    android
    log)