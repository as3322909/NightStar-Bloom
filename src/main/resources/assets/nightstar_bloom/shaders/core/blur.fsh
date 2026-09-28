#version 150
uniform sampler2D Source;
uniform sampler2D Aux;
layout(std140) uniform Params { mat4 Matrix; vec4 Settings; vec4 Tint; };
in vec2 uv;
out vec4 fragColor;
vec4 sampleSource(vec2 p){
 // Zero outside screen, rather than repeating/clamping an edge emitter indefinitely.
 if(any(lessThan(p,vec2(0)))||any(greaterThan(p,vec2(1))))return vec4(0);
 vec4 c=texture(Source,p);return vec4(c.rgb*c.rgb,c.a);
}
void main(){
 if(Settings.w>1.5&&Settings.w<3.5){
  vec2 axis=Settings.w>2.5?vec2(0,Settings.y):vec2(Settings.x,0);
  // Unit-spaced taps even at radius=2: wider kernels never become sparse replicas.
  float sigma=max(0.35,2.0*Settings.z);
  vec4 c=vec4(0);float sum=0;
  for(int i=-16;i<=16;i++){
   float weight=exp(-float(i*i)/(2.0*sigma*sigma));
   c+=sampleSource(uv+axis*float(i))*weight;sum+=weight;
  }
  c/=sum;fragColor=vec4(sqrt(max(c.rgb,vec3(0))),c.a);return;
 }
 vec2 d=Settings.xy*0.5;
 vec4 c=sampleSource(uv)*0.25;
 c+=(sampleSource(uv+vec2(d.x,0))+sampleSource(uv-vec2(d.x,0))
   +sampleSource(uv+vec2(0,d.y))+sampleSource(uv-vec2(0,d.y)))*0.125;
 c+=(sampleSource(uv+d)+sampleSource(uv-d)
   +sampleSource(uv+vec2(d.x,-d.y))+sampleSource(uv+vec2(-d.x,d.y)))*0.0625;
 if(Settings.w>3.5){
  vec4 expanded=c;
  expanded=max(expanded,sampleSource(uv+vec2(Settings.x,0)));
  expanded=max(expanded,sampleSource(uv-vec2(Settings.x,0)));
  expanded=max(expanded,sampleSource(uv+vec2(0,Settings.y)));
  expanded=max(expanded,sampleSource(uv-vec2(0,Settings.y)));
  c=mix(c,expanded,0.2);
 }else if(Settings.w>0.5){vec4 a=texture(Aux,uv);c=mix(c,vec4(a.rgb*a.rgb,a.a),0.35);}
 fragColor=vec4(sqrt(max(c.rgb,vec3(0))),c.a);
}
