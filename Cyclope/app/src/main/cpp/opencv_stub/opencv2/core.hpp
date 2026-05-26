#pragma once
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>

#define CV_8UC1 0
#define CV_8UC4 24

namespace cv {

enum RotateFlags {
    ROTATE_90_CLOCKWISE = 0,
    ROTATE_180 = 1,
    ROTATE_90_COUNTERCLOCKWISE = 2
};

struct Size {
    int width, height;
    Size() : width(0), height(0) {}
    Size(int w, int h) : width(w), height(h) {}
};

struct Point {
    int x, y;
    Point(int x = 0, int y = 0) : x(x), y(y) {}
};

struct Scalar {
    double val[4];
    Scalar(double v0=0, double v1=0, double v2=0, double v3=0) {
        val[0]=v0; val[1]=v1; val[2]=v2; val[3]=v3;
    }
};

struct Rect {
    int x, y, width, height;
    Rect() : x(0), y(0), width(0), height(0) {}
    Rect(int x, int y, int w, int h) : x(x), y(y), width(w), height(h) {}
    Rect operator&(const Rect& r) const {
        int x1 = std::max(x, r.x), y1 = std::max(y, r.y);
        int x2 = std::min(x + width,  r.x + r.width);
        int y2 = std::min(y + height, r.y + r.height);
        if (x2 <= x1 || y2 <= y1) return Rect(0, 0, 0, 0);
        return Rect(x1, y1, x2 - x1, y2 - y1);
    }
    Rect& operator&=(const Rect& r) { *this = *this & r; return *this; }
};

class Mat {
public:
    int rows, cols;
    uint8_t* data;

    Mat() : rows(0), cols(0), data(nullptr), _owned(false), _type(CV_8UC4) {}

    Mat(int rows, int cols, int type)
        : rows(rows), cols(cols), _owned(true), _type(type) {
        data = new uint8_t[rows * cols * bpp(type)]();
    }

    Mat(int rows, int cols, int type, void* ptr, size_t /*step*/ = 0)
        : rows(rows), cols(cols), data(static_cast<uint8_t*>(ptr)),
          _owned(false), _type(type) {}

    Mat(const Mat& o)
        : rows(o.rows), cols(o.cols), data(o.data), _owned(false), _type(o._type) {}

    Mat& operator=(const Mat& o) {
        if (this != &o) {
            release();
            rows = o.rows; cols = o.cols; _type = o._type;
            data = o.data; _owned = false;
        }
        return *this;
    }

    ~Mat() { release(); }

    bool empty() const { return data == nullptr || rows == 0 || cols == 0; }

    void release() {
        if (_owned && data) { delete[] data; }
        data = nullptr; rows = 0; cols = 0; _owned = false;
    }

    int type() const { return _type; }

    template<typename T>
    T* ptr(int row) {
        return reinterpret_cast<T*>(data + row * cols * sizeof(T));
    }

    template<typename T>
    const T* ptr(int row) const {
        return reinterpret_cast<const T*>(data + row * cols * sizeof(T));
    }

    // Sub-matrix view — stub (display path only, never called headless)
    Mat operator()(const Rect&) const { return Mat(); }

    void copyTo(Mat& dst) const {
        if (empty()) { dst.release(); return; }
        if (dst._owned && dst.data) delete[] dst.data;
        dst.rows = rows; dst.cols = cols; dst._type = _type;
        dst._owned = true;
        int sz = rows * cols * bpp(_type);
        dst.data = new uint8_t[sz];
        memcpy(dst.data, data, sz);
    }

    Mat& operator*=(double) { return *this; } // stub

private:
    bool _owned;
    int  _type;

    static int bpp(int type) { return (type == CV_8UC4) ? 4 : 1; }
};

// getTextSize is also in imgproc but declared here so core.hpp is self-contained
// when imgproc.hpp includes core.hpp
inline Size getTextSize(const std::string&, int, double, int, int* baseLine) {
    if (baseLine) *baseLine = 0;
    return Size(0, 0);
}

} // namespace cv