#version 150
uniform sampler2D Source;
uniform sampler2D Aux;
uniform sampler2D Core;
uniform sampler2D Narrow;
layout(std140) uniform Params { mat4 Matrix; vec4 Settings; vec4 Tint; };
in vec2 uv;
out vec4 fragColor;
vec3 linearize(vec3 c){return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c));}
vec3 encode(vec3 c){return mix(c*12.92,1.055*pow(max(c,vec3(0)),vec3(1.0/2.4))-0.055,step(vec3(0.0031308),c));}
void main(){
 vec4 scene=texture(Aux,uv);
 if(Settings.w>2.5){fragColor=vec4(mix(scene.rgb,vec3(1,0,1),texture(Core,uv).a>0.0?0.65:0.0),1);return;} // debug overlay: same-frame core coverage
 if(Settings.w>1.5){fragColor=scene;return;}
 vec4 coreSample=texture(Core,uv);vec3 core=coreSample.rgb*coreSample.rgb*4.0;
 if(Settings.w>0.5){fragColor=vec4(encode(clamp(core,0.0,1.0)),1);return;}
 if(Tint.a==0.0){fragColor=scene;return;}
 vec3 energy=vec3(0);
 if(Settings.z>0.0){
  vec3 n=texture(Narrow,uv).rgb,a=texture(Source,uv).rgb;
  // Preserve crystal texture contrast: blur primarily contributes outside emitter coverage.
  vec3 nearEnergy=n*n*4.0,outerEnergy=a*a*4.0;
  // Smoothly reject weak tails; no hard cutoff or opaque fog sheet.
  float outerLuma=max(outerEnergy.r,max(outerEnergy.g,outerEnergy.b));
  outerEnergy*=outerLuma/(outerLuma+0.012);
  energy+=(nearEnergy*Settings.y+outerEnergy*Settings.z)*(1.0-0.85*coreSample.a);
 }
 energy=max(vec3(0),energy*Tint.rgb*Tint.a);
 // Do not round-trip untouched scene pixels.
 if(max(energy.r,max(energy.g,energy.b))==0.0&&coreSample.a==0.0){fragColor=scene;return;}
 vec3 base=linearize(scene.rgb);
 // Bring only emitter pixels toward full-bright texture radiance. This preserves
 // dark/light facet ratios instead of bleaching the whole crystal toward white.
 vec3 emission=clamp(core*Tint.rgb*Settings.x,0.0,1.0);
 base=mix(base,max(base,emission),coreSample.a*(1.0-exp(-Tint.a*1.8)));
 fragColor=vec4(encode(base+(1.0-clamp(base,0.0,1.0))*(1.0-exp(-energy))),scene.a);
}
