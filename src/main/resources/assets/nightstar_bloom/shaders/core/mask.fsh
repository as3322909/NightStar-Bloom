#version 150
uniform sampler2D Source;
layout(std140) uniform Params { mat4 Matrix; vec4 Settings; vec4 Tint; vec4 FlowStart; vec4 FlowDir; };
in vec2 uv;in float flowCoord;
out vec4 fragColor;
vec3 linearize(vec3 c){return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c));}
void main(){
 vec4 c=texture(Source,uv);if(c.a<0.1)discard;
 vec3 energy=linearize(c.rgb)*Tint.rgb*Tint.a;
 if(Settings.z>0.5){
  // A narrow UV-space highlight travels over the crystal without changing its geometry.
  float p=fract(flowCoord-Settings.y);
  float d=min(p,1.0-p);
  float band=1.0-smoothstep(0.02,0.14,d);
  energy*=1.0+2.0*band;
 }
 if(Settings.x<0.5)energy=mix(energy,vec3(max(energy.r,max(energy.g,energy.b))),0.03);
 // Square-root encoding preserves dim halo precision in RGBA8; convolution decodes to linear.
 fragColor=vec4(sqrt(clamp(energy*c.a*0.25,0.0,1.0)),c.a);
}
