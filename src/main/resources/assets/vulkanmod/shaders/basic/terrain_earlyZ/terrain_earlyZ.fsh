#version 450

layout(early_fragment_tests) in;

#include "light.glsl"
#include "fog.glsl"

layout(binding = 3) uniform sampler2D Sampler0;

layout(binding = 1) uniform UBO {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
    float AlphaCutout;
    ivec2 TextureSize;
    vec2 TexelSize;
    int UseRgss;
};

vec4 sampleNearest(sampler2D sampler1, vec2 uv, vec2 pixelSize, vec2 texSize, vec2 du, vec2 dv, vec2 invTexelScreenSize) {
    // Convert our UV back up to texel coordinates and find out how far over we are from the center of each pixel
    vec2 uvTexelCoords = uv * texSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    // Move our offset closer to the texel center based on texel size on screen
    texelOffset = clamp((texelOffset - 0.5) * pixelSize * invTexelScreenSize + 0.5, 0.0, 1.0);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(sampler1, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 texSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 invTexelScreenSize = inversesqrt(max(du * du + dv * dv, vec2(1e-20)));
    return sampleNearest(source, uv, pixelSize, texSize, du, dv, invTexelScreenSize);
}

#ifdef VOOLKAN_RGSS
// Rotated Grid Super-Sampling
vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize, vec2 texSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    vec2 dd = du * du + dv * dv;
    vec2 invTexelScreenSize = inversesqrt(max(dd, vec2(1e-20)));
    vec2 texelScreenSize = dd * invTexelScreenSize;
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);
    float blendFactor = smoothstep(minPixelSize, minPixelSize * 2.0, maxTexelSize);

    vec4 nearestColor = sampleNearest(source, uv, pixelSize, texSize, du, dv, invTexelScreenSize);
    if (blendFactor <= 0.0) {
        return nearestColor;
    }

    float duLength = length(du);
    float dvLength = length(dv);
    float effectiveDerivative = sqrt(duLength * dvLength);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    float mipLevelLow = floor(mipLevelExact);
    float mipLevelHigh = mipLevelLow + 1.0;
    float mipBlend = fract(mipLevelExact);

    const vec2 offsets[4] = vec2[](
        vec2(0.125, 0.375),
        vec2(-0.125, -0.375),
        vec2(0.375, -0.125),
        vec2(-0.375, 0.125)
    );

    vec4 rgssColorLow = vec4(0.0);
    vec4 rgssColorHigh = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColorLow += textureLod(source, sampleUV, mipLevelLow);
        rgssColorHigh += textureLod(source, sampleUV, mipLevelHigh);
    }
    rgssColorLow *= 0.25;
    rgssColorHigh *= 0.25;

    vec4 rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);
    return mix(nearestColor, rgssColor, blendFactor);
}
#endif

layout (location = 0) in vec4 vertexColor;
layout (location = 1) in vec2 texCoord0;
layout (location = 2) in float sphericalVertexDistance;
layout (location = 3) in float cylindricalVertexDistance;
layout (location = 4) in flat float fadeFactor;

layout (location = 0) out vec4 fragColor;

void main() {
    vec2 texSize = vec2(TextureSize);
#ifdef VOOLKAN_RGSS
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, TexelSize, texSize) : sampleNearest(Sampler0, texCoord0, TexelSize, texSize)) * vertexColor;
#else
    vec4 color = sampleNearest(Sampler0, texCoord0, TexelSize, texSize) * vertexColor;
#endif
    if (fadeFactor < 1.0) {
        color = mix(FogColor * vec4(1.0, 1.0, 1.0, color.a), color, fadeFactor);
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
