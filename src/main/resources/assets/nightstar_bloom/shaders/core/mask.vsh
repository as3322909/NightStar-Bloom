#version 150
in vec3 Position;
in vec2 UV0;
layout(std140) uniform Params { mat4 Matrix; vec4 Settings; vec4 Tint; vec4 FlowStart; vec4 FlowDir; };
out vec2 uv;
out float flowCoord;
void main(){uv=UV0;flowCoord=FlowDir.w>0.5?dot(Position-FlowStart.xyz,FlowDir.xyz)*FlowDir.w:UV0.y;gl_Position=Matrix*vec4(Position,1.0);gl_Position.z-=0.000001*gl_Position.w;}
