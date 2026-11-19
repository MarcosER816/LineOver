package dev.mark.lineover.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Layer kinds (Stage 13); Text and Shape are reserved for Stages 23 and 24 (they draw nothing yet). */
@Serializable
enum class LayerType(
    val label: String,
) {
    @SerialName("background")
    Background("Photo"),

    @SerialName("raster")
    Raster("Layer"),

    @SerialName("adjustment")
    Adjustment("Adjustment"),

    @SerialName("group")
    Group("Group"),

    @SerialName("smart")
    SmartObject("Smart object"),

    @SerialName("text")
    Text("Text"),

    @SerialName("shape")
    Shape("Shape"),
}

/** Where layers blend (a document setting; Photoshop blends gamma-encoded values). */
@Serializable
enum class BlendSpace {
    @SerialName("gamma")
    Gamma,

    @SerialName("linear")
    Linear,
}

/** The 27 blend modes, in Photoshop's menu order (layers.h, blend_reference.MODES). */
@Serializable
enum class BlendMode(
    val label: String,
) {
    @SerialName("normal")
    Normal("Normal"),

    @SerialName("dissolve")
    Dissolve("Dissolve"),

    @SerialName("darken")
    Darken("Darken"),

    @SerialName("multiply")
    Multiply("Multiply"),

    @SerialName("color_burn")
    ColorBurn("Color Burn"),

    @SerialName("linear_burn")
    LinearBurn("Linear Burn"),

    @SerialName("darker_color")
    DarkerColor("Darker Color"),

    @SerialName("lighten")
    Lighten("Lighten"),

    @SerialName("screen")
    Screen("Screen"),

    @SerialName("color_dodge")
    ColorDodge("Color Dodge"),

    @SerialName("linear_dodge")
    LinearDodge("Linear Dodge (Add)"),

    @SerialName("lighter_color")
    LighterColor("Lighter Color"),

    @SerialName("overlay")
    Overlay("Overlay"),

    @SerialName("soft_light")
    SoftLight("Soft Light"),

    @SerialName("hard_light")
    HardLight("Hard Light"),

    @SerialName("vivid_light")
    VividLight("Vivid Light"),

    @SerialName("linear_light")
    LinearLight("Linear Light"),

    @SerialName("pin_light")
    PinLight("Pin Light"),

    @SerialName("hard_mix")
    HardMix("Hard Mix"),

    @SerialName("difference")
    Difference("Difference"),

    @SerialName("exclusion")
    Exclusion("Exclusion"),

    @SerialName("subtract")
    Subtract("Subtract"),

    @SerialName("divide")
    Divide("Divide"),

    @SerialName("hue")
    Hue("Hue"),

    @SerialName("saturation")
    Saturation("Saturation"),

    @SerialName("color")
    Color("Color"),

    @SerialName("luminosity")
    Luminosity("Luminosity"),
    ;

    companion object {
        /** Menu groups as Photoshop separates them (normal, darken, lighten, contrast, inversion, component). */
        val GROUPS: List<List<BlendMode>> =
            listOf(
                listOf(Normal, Dissolve),
                listOf(Darken, Multiply, ColorBurn, LinearBurn, DarkerColor),
                listOf(Lighten, Screen, ColorDodge, LinearDodge, LighterColor),
                listOf(Overlay, SoftLight, HardLight, VividLight, LinearLight, PinLight, HardMix),
                listOf(Difference, Exclusion, Subtract, Divide),
                listOf(Hue, Saturation, Color, Luminosity),
            )
    }
}
