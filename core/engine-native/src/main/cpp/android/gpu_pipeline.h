// GPU side of the ops and of the display transform. Render thread only.
#pragma once

#include <GLES3/gl3.h>

#include <map>
#include <string>
#include <utility>

#include "lineover/color.h"
#include "lineover/ops.h"

namespace lo::android {

class GpuPipeline {
public:
    bool init(std::string* error);
    void release();

    bool halfFloat() const { return halfFloat_; }

    // Runs `ops` on `source` (width x height) into `target` (same size), one pass per
    // non-identity op (ping-pong through a scratch texture). With no work to do it copies.
    bool runOps(const OpList& ops, GLuint source, GLuint target, int width, int height, std::string* error);

    // Draws `texture` (working space) as the quad image-pixel rect `rect` (x, y, w, h)
    // through `imageToNdc` (row-major 3x3 mapping original-image pixels to NDC), with the
    // display transform: Rec.2020 -> display primaries, clip, and the sRGB transfer when
    // `encode` (false when the EGL surface encodes itself). Output is premultiplied.
    void drawImage(GLuint texture, const float rect[4], const float imageToNdc[9], color::Display display, bool encode);

private:
    GLuint programFor(const std::string& declarations, const std::string& body, std::string* error);
    GLuint scratch(int width, int height);

    bool halfFloat_ = true;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;
    GLuint fbo_ = 0;
    GLuint displayProgram_ = 0;
    GLint displayTex_ = -1, displayMatrix_ = -1, displayToDisplay_ = -1, displayEncode_ = -1;
    std::map<std::string, GLuint> opPrograms_;
    std::map<std::pair<int, int>, GLuint> scratch_;
};

}  // namespace lo::android
