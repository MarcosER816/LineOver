#include "lineover/graph.h"
#include "lineover/color_ops.h"
#include "lineover/light.h"

#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <map>

namespace lo {
namespace {

// A minimal JSON reader: objects (key order kept), arrays, strings, numbers, booleans,
// null. Enough for the graph schema; no external dependency.
struct Value {
    enum class Kind { Null, Bool, Number, String, Array, Object } kind = Kind::Null;
    bool boolean = false;
    double number = 0.0;
    std::string text;
    std::vector<Value> items;
    std::vector<std::pair<std::string, Value>> members;

    const Value* find(const std::string& key) const {
        for (const auto& member : members) {
            if (member.first == key) return &member.second;
        }
        return nullptr;
    }
};

class Reader {
public:
    explicit Reader(const std::string& s) : s_(s) {}

    bool parse(Value* out, std::string* error) {
        skipSpace();
        if (!value(out)) {
            *error = error_.empty() ? "malformed JSON" : error_;
            return false;
        }
        skipSpace();
        if (pos_ != s_.size()) {
            *error = "trailing characters at " + std::to_string(pos_);
            return false;
        }
        return true;
    }

private:
    const std::string& s_;
    std::size_t pos_ = 0;
    std::string error_;
    int depth_ = 0;

    void skipSpace() {
        while (pos_ < s_.size() && (s_[pos_] == ' ' || s_[pos_] == '\n' || s_[pos_] == '\r' || s_[pos_] == '\t')) ++pos_;
    }

    bool fail(const std::string& what) {
        if (error_.empty()) error_ = what + " at " + std::to_string(pos_);
        return false;
    }

    bool literal(const char* word) {
        std::size_t n = std::char_traits<char>::length(word);
        if (s_.compare(pos_, n, word) != 0) return fail("unexpected token");
        pos_ += n;
        return true;
    }

    bool value(Value* out) {
        if (++depth_ > 64) return fail("nesting too deep");
        skipSpace();
        if (pos_ >= s_.size()) return fail("unexpected end");
        bool ok;
        const char c = s_[pos_];
        if (c == '{') {
            ok = object(out);
        } else if (c == '[') {
            ok = array(out);
        } else if (c == '"') {
            out->kind = Value::Kind::String;
            ok = string(&out->text);
        } else if (c == 't') {
            out->kind = Value::Kind::Bool;
            out->boolean = true;
            ok = literal("true");
        } else if (c == 'f') {
            out->kind = Value::Kind::Bool;
            ok = literal("false");
        } else if (c == 'n') {
            ok = literal("null");
        } else {
            ok = number(out);
        }
        --depth_;
        return ok;
    }

    bool object(Value* out) {
        out->kind = Value::Kind::Object;
        ++pos_;
        skipSpace();
        if (pos_ < s_.size() && s_[pos_] == '}') {
            ++pos_;
            return true;
        }
        while (true) {
            skipSpace();
            std::string key;
            if (pos_ >= s_.size() || s_[pos_] != '"' || !string(&key)) return fail("expected key");
            skipSpace();
            if (pos_ >= s_.size() || s_[pos_] != ':') return fail("expected ':'");
            ++pos_;
            Value member;
            if (!value(&member)) return false;
            out->members.emplace_back(std::move(key), std::move(member));
            skipSpace();
            if (pos_ < s_.size() && s_[pos_] == ',') {
                ++pos_;
                continue;
            }
            if (pos_ < s_.size() && s_[pos_] == '}') {
                ++pos_;
                return true;
            }
            return fail("expected ',' or '}'");
        }
    }

    bool array(Value* out) {
        out->kind = Value::Kind::Array;
        ++pos_;
        skipSpace();
        if (pos_ < s_.size() && s_[pos_] == ']') {
            ++pos_;
            return true;
        }
        while (true) {
            Value item;
            if (!value(&item)) return false;
            out->items.push_back(std::move(item));
            skipSpace();
            if (pos_ < s_.size() && s_[pos_] == ',') {
                ++pos_;
                continue;
            }
            if (pos_ < s_.size() && s_[pos_] == ']') {
                ++pos_;
                return true;
            }
            return fail("expected ',' or ']'");
        }
    }

    bool string(std::string* out) {
        ++pos_;  // opening quote
        while (pos_ < s_.size()) {
            const char c = s_[pos_++];
            if (c == '"') return true;
            if (c != '\\') {
                out->push_back(c);
                continue;
            }
            if (pos_ >= s_.size()) break;
            const char e = s_[pos_++];
            switch (e) {
                case '"': out->push_back('"'); break;
                case '\\': out->push_back('\\'); break;
                case '/': out->push_back('/'); break;
                case 'b': out->push_back('\b'); break;
                case 'f': out->push_back('\f'); break;
                case 'n': out->push_back('\n'); break;
                case 'r': out->push_back('\r'); break;
                case 't': out->push_back('\t'); break;
                case 'u': {
                    if (pos_ + 4 > s_.size()) return fail("bad \\u escape");
                    const unsigned code = static_cast<unsigned>(std::strtoul(s_.substr(pos_, 4).c_str(), nullptr, 16));
                    pos_ += 4;
                    // Ids and op names are ASCII; other code points are kept as UTF-8.
                    if (code < 0x80) {
                        out->push_back(static_cast<char>(code));
                    } else if (code < 0x800) {
                        out->push_back(static_cast<char>(0xC0 | (code >> 6)));
                        out->push_back(static_cast<char>(0x80 | (code & 0x3F)));
                    } else {
                        out->push_back(static_cast<char>(0xE0 | (code >> 12)));
                        out->push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
                        out->push_back(static_cast<char>(0x80 | (code & 0x3F)));
                    }
                    break;
                }
                default: return fail("bad escape");
            }
        }
        return fail("unterminated string");
    }

    bool number(Value* out) {
        const char* begin = s_.c_str() + pos_;
        char* end = nullptr;
        const double v = std::strtod(begin, &end);
        if (end == begin) return fail("expected a value");
        out->kind = Value::Kind::Number;
        out->number = v;
        pos_ += static_cast<std::size_t>(end - begin);
        return true;
    }
};

void writeString(const std::string& s, std::string* out) {
    out->push_back('"');
    for (const char c : s) {
        switch (c) {
            case '"': out->append("\\\""); break;
            case '\\': out->append("\\\\"); break;
            case '\n': out->append("\\n"); break;
            case '\r': out->append("\\r"); break;
            case '\t': out->append("\\t"); break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buffer[8];
                    std::snprintf(buffer, sizeof(buffer), "\\u%04x", static_cast<unsigned>(c));
                    out->append(buffer);
                } else {
                    out->push_back(c);
                }
        }
    }
    out->push_back('"');
}

}  // namespace

std::string formatKotlinFloat(float value) {
    if (std::isnan(value)) return "NaN";
    if (std::isinf(value)) return value > 0 ? "Infinity" : "-Infinity";
    if (value == 0.0f) return std::signbit(value) ? "-0.0" : "0.0";
    // Shortest decimal that reads back to the same float.
    char buffer[32];
    for (int precision = 1; precision <= 9; ++precision) {
        std::snprintf(buffer, sizeof(buffer), "%.*e", precision - 1, static_cast<double>(value));
        if (std::strtof(buffer, nullptr) == value) break;
    }
    // buffer is d.ddde±XX: split into digits and exponent.
    std::string text(buffer);
    const std::size_t ePos = text.find('e');
    std::string mantissa = text.substr(0, ePos);
    const int exponent = std::atoi(text.c_str() + ePos + 1);
    const bool negative = mantissa[0] == '-';
    if (negative) mantissa.erase(0, 1);
    std::string digits;
    for (const char c : mantissa) {
        if (c != '.') digits.push_back(c);
    }
    while (digits.size() > 1 && digits.back() == '0') digits.pop_back();
    const float magnitude = std::fabs(value);
    std::string result;
    if (magnitude >= 1e-3f && magnitude < 1e7f) {
        // Plain notation, at least one digit after the point.
        if (exponent >= 0) {
            std::string integral = digits.substr(0, std::min<std::size_t>(digits.size(), exponent + 1));
            while (static_cast<int>(integral.size()) < exponent + 1) integral.push_back('0');
            std::string fraction = digits.size() > static_cast<std::size_t>(exponent + 1) ? digits.substr(exponent + 1) : "0";
            result = integral + "." + fraction;
        } else {
            result = "0." + std::string(static_cast<std::size_t>(-exponent - 1), '0') + digits;
        }
    } else {
        std::string fraction = digits.size() > 1 ? digits.substr(1) : "0";
        result = digits.substr(0, 1) + "." + fraction + "E" + std::to_string(exponent);
    }
    return negative ? "-" + result : result;
}

bool parseGraph(const std::string& json, Graph* out, std::string* error) {
    Value root;
    Reader reader(json);
    if (!reader.parse(&root, error)) return false;
    if (root.kind != Value::Kind::Object) {
        *error = "graph is not an object";
        return false;
    }
    Graph graph;
    if (const Value* version = root.find("schemaVersion"); version != nullptr && version->kind == Value::Kind::Number) {
        graph.schemaVersion = static_cast<int>(version->number);
    }
    if (const Value* layers = root.find("layers"); layers != nullptr) {
        if (layers->kind != Value::Kind::Array) {
            *error = "layers is not an array";
            return false;
        }
        for (const Value& layerValue : layers->items) {
            if (layerValue.kind != Value::Kind::Object) {
                *error = "layer is not an object";
                return false;
            }
            Layer layer;
            if (const Value* id = layerValue.find("id"); id != nullptr && id->kind == Value::Kind::String) layer.id = id->text;
            if (const Value* ops = layerValue.find("ops"); ops != nullptr && ops->kind == Value::Kind::Array) {
                for (const Value& opValue : ops->items) {
                    const Value* name = opValue.find("op");
                    if (opValue.kind != Value::Kind::Object || name == nullptr || name->kind != Value::Kind::String) {
                        *error = "op without a name";
                        return false;
                    }
                    OpParams params;
                    params.op = name->text;
                    for (const auto& member : opValue.members) {
                        if (member.first != "op" && member.second.kind == Value::Kind::Number) {
                            params.params.emplace_back(member.first, member.second.number);
                        } else if (member.second.kind == Value::Kind::Array) {
                            std::vector<double> numbers;
                            for (const Value& item : member.second.items) {
                                if (item.kind == Value::Kind::Number) numbers.push_back(item.number);
                            }
                            params.arrays.emplace_back(member.first, std::move(numbers));
                        }
                    }
                    layer.ops.push_back(std::move(params));
                }
            }
            graph.layers.push_back(std::move(layer));
        }
    }
    *out = std::move(graph);
    return true;
}

std::string serializeGraph(const Graph& graph) {
    std::string out = "{\"schemaVersion\":" + std::to_string(graph.schemaVersion) + ",\"layers\":[";
    for (std::size_t l = 0; l < graph.layers.size(); ++l) {
        if (l > 0) out.push_back(',');
        out.append("{\"id\":");
        writeString(graph.layers[l].id, &out);
        out.append(",\"ops\":[");
        const auto& ops = graph.layers[l].ops;
        for (std::size_t o = 0; o < ops.size(); ++o) {
            if (o > 0) out.push_back(',');
            out.append("{\"op\":");
            writeString(ops[o].op, &out);
            for (const auto& param : ops[o].params) {
                out.push_back(',');
                writeString(param.first, &out);
                out.push_back(':');
                out.append(formatKotlinFloat(static_cast<float>(param.second)));
            }
            for (const auto& array : ops[o].arrays) {
                out.push_back(',');
                writeString(array.first, &out);
                out.append(":[");
                for (std::size_t i = 0; i < array.second.size(); ++i) {
                    if (i > 0) out.push_back(',');
                    out.append(formatKotlinFloat(static_cast<float>(array.second[i])));
                }
                out.push_back(']');
            }
            out.push_back('}');
        }
        out.append("]}");
    }
    out.append("]}");
    return out;
}

std::unique_ptr<OpList> buildOps(const Graph& graph, std::string* skipped) {
    auto list = std::make_unique<OpList>();
    for (const Layer& layer : graph.layers) {
        for (const OpParams& params : layer.ops) {
            auto param = [&](const char* key, double fallback) {
                for (const auto& p : params.params) {
                    if (p.first == key) return p.second;
                }
                return fallback;
            };
            if (params.op == "identity") {
                list->add(makeIdentity());
            } else if (params.op == "exposure_test") {
                list->add(makeExposureTest(static_cast<float>(param("stops", 0.0))));
            } else if (params.op == "exposure") {
                list->add(makeExposure(static_cast<float>(param("ev", 0.0))));
            } else if (params.op == "contrast") {
                list->add(makeContrast(static_cast<float>(param("amount", 0.0))));
            } else if (params.op == "brightness") {
                list->add(makeBrightness(static_cast<float>(param("amount", 0.0))));
            } else if (params.op == "whites") {
                list->add(makeWhites(static_cast<float>(param("amount", 0.0))));
            } else if (params.op == "blacks") {
                list->add(makeBlacks(static_cast<float>(param("amount", 0.0))));
            } else if (params.op == "white_balance") {
                list->add(makeWhiteBalance(static_cast<float>(param("temperature", kWbDefaultKelvin)),
                                           static_cast<float>(param("tint", 0.0))));
            } else if (params.op == "vibrance") {
                list->add(makeVibrance(static_cast<float>(param("amount", 0.0))));
            } else if (params.op == "saturation") {
                list->add(makeSaturation(static_cast<float>(param("amount", 0.0))));
            } else if (params.op == "hsl_mixer") {
                auto bands = [&](const char* key) {
                    std::array<float, kHslBands> values{};
                    for (const auto& a : params.arrays) {
                        if (a.first != key) continue;
                        for (std::size_t i = 0; i < values.size() && i < a.second.size(); ++i) {
                            values[i] = static_cast<float>(a.second[i]);
                        }
                    }
                    return values;
                };
                list->add(makeHslMixer(bands("hue"), bands("saturation"), bands("luminance")));
            } else if (params.op == "invert") {
                list->add(makeInvert());
            } else if (params.op == "posterize") {
                list->add(makePosterize(static_cast<float>(param("levels", 0.0))));
            } else if (params.op == "threshold") {
                list->add(makeThreshold(static_cast<float>(param("level", 0.5))));
            } else if (params.op == "highlights_shadows") {
                list->add(makeHighlightsShadows(static_cast<float>(param("highlights", 0.0)),
                                                static_cast<float>(param("shadows", 0.0))));
            } else if (skipped != nullptr) {
                if (!skipped->empty()) skipped->push_back(',');
                skipped->append(params.op);
            }
        }
    }
    return list;
}

}  // namespace lo
