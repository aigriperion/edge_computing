#pragma once
#include "core.hpp"
#include <string>

namespace cv {

const int INTER_LINEAR       = 1;
const int FONT_HERSHEY_SIMPLEX = 0;

// All stubs — display path only, never called in headless mode
inline void rotate(const Mat&, Mat&, int) {}
inline void resize(const Mat&, Mat&, Size, double=0, double=0, int=INTER_LINEAR) {}
inline void putText(Mat&, const std::string&, Point, int, double, Scalar, int=1,
                    int=0, bool=false) {}

} // namespace cv