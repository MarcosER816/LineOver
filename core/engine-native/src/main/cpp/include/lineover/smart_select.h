// Smart selections (Stage 16): joint bilateral upsampling of model masks, guided-filter matting
// (refine edge), the live-wire of the magnetic lasso (Intelligent Scissors) and GrabCut for quick
// selection (Gaussian mixtures + a Boykov-Kolmogorov graph cut). tools/golden/smart_reference.py
// is the reference (ctest smart_select_test); GrabCut is held to cv2.grabCut by IoU.
#pragma once

#include <cstdint>
#include <vector>

namespace lo::smart {

// The guide of every algorithm here: sqrt(clamp(rgb, 0, 1)) of RGBA working-space pixels, 3 per pixel.
std::vector<double> guide(const float* rgba, int width, int height);

// The mean of the guide pixels whose centres fall in each of lw x lh low pixels' footprints (the
// nearest guide pixel when none does).
std::vector<double> lowGuide(const std::vector<double>& guide, int width, int height, int lw, int lh);

// Joint bilateral upsampling (Kopf et al. 2007) of [low] (lw x lh) to width x height pixels whose
// guide is [guide]; [lowGuide] is lowGuide(guide, ...). sigma_s = 1 low pixel, sigma_r = 0.1, reach 2.
void jointBilateralUpsample(const float* low, int lw, int lh, const std::vector<double>& lowGuide,
                            const std::vector<double>& guide, int width, int height, float* out);

// A colour guided filter (He et al.) of [s] with [guide], box radius r (BORDER_REFLECT_101).
void guidedMatte(const float* s, const std::vector<double>& guide, int width, int height, int r, double eps, float* out);

// Refine edge: the guided matte (radius in buffer px, at least 1) and contrast 0..100 around 0.5.
void refine(std::vector<float>* s, const float* rgba, int width, int height, double radius, double contrast);

// The magnetic lasso's live-wire on a grey image (0..1). setAnchor runs Dijkstra from the anchor
// over the pixels within [reach] of it (all of them when reach <= 0); pathTo reads a path back.
class LiveWire {
public:
    void setImage(const float* grey, int width, int height);
    void setAnchor(int x, int y, int reach);
    // Pixels x0, y0, x1, y1, ... from the anchor to (x, y) (clamped into the searched window).
    std::vector<int> pathTo(int x, int y) const;
    bool empty() const { return width_ == 0; }
    int width() const { return width_; }
    int height() const { return height_; }

private:
    double linkCost(int p, int q) const;
    int width_ = 0, height_ = 0;
    std::vector<double> fz_, fg_, dx_, dy_;
    int x0_ = 0, y0_ = 0, x1_ = 0, y1_ = 0, anchor_ = -1;
    std::vector<int> parent_;
};

// A max-flow / min-cut graph (Boykov-Kolmogorov 2004; the structure of OpenCV's GCGraph).
class GraphCut {
public:
    explicit GraphCut(int vertices, int edges = 0);
    void addTerminal(int v, double source, double sink);
    void addEdge(int a, int b, double w, double reverse);
    double maxFlow();
    bool inSource(int v) const;

private:
    struct Vertex {
        Vertex* next = nullptr;
        int parent = 0;
        int first = 0;
        int ts = 0;
        int dist = 0;
        double weight = 0.0;
        std::uint8_t t = 0;
    };
    struct Edge {
        int dst = 0;
        int next = 0;
        double weight = 0.0;
    };
    std::vector<Vertex> v_;
    std::vector<Edge> e_;
    double flow_ = 0.0;
};

// GrabCut labels (cv2's GC_*).
enum : std::uint8_t { kBgd = 0, kFgd = 1, kPrBgd = 2, kPrFgd = 3 };

// GrabCut (Rother et al. 2004, as cv2.grabCut with GC_INIT_WITH_MASK) of rgb (0..255, 3 per
// pixel) from [mask] (updated in place) for [iterations].
void grabCut(const std::vector<double>& rgb, int width, int height, std::vector<std::uint8_t>* mask, int iterations);

}  // namespace lo::smart
