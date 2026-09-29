package com.nightstar.bloom;
import org.joml.Matrix4f;
/** Standalone regression checks, no Minecraft launch or third-party fixtures required. */
public final class AxPoseTest {
    static void check(boolean condition){if(!condition)throw new AssertionError();}
    public static void main(String[] args){
        AxPose p=new AxPose(new String[]{"crystal","facet"});Object a=new Object(),b=new Object();
        Matrix4f m=new Matrix4f().translate(2,3,4).rotateY(.5f).scale(1,2,3);
        p.begin(a,1,1);check(p.capture("crystal",m));m.identity();
        check(p.get(0,a,1,1).m30()==2);check(p.get(1,a,1,1)==null);
        check(p.get(0,b,1,1)==null);check(p.get(0,a,2,1)==null);check(p.get(0,a,1,2)==null);
        p.begin(b,1,2);check(p.get(0,b,1,2)==null);p.capture("crystal",m.scale(0));
        check(p.get(0,b,1,2)!=null&&p.get(0,b,1,2).determinant()==0);
        check(p.get(0,a,1,1)==null);p.clear();check(p.get(0,b,1,2)==null);
        p.begin(a,2,3);p.capture("crystal",new Matrix4f());p.capture("crystal",new Matrix4f());check(p.get(0,a,2,3)==null);
        p.begin(a,2,4);p.capture("facet",new Matrix4f().m00(Float.NaN));check(p.get(1,a,2,4)==null);
        p.begin(a,3,5);p.capture("crystal",new Matrix4f().zero());
        check(p.get(0,a,3,5).m00()==0);p.reveal("crystal",new Matrix4f().translate(4,0,0));check(p.get(0,a,3,5).m30()==4);
        Matrix4f root=new Matrix4f().translate(9,8,7).rotateY(.4f).scale(2,3,4);
        Matrix4f local=new Matrix4f().translate(1,2,3).scale(0);
        Matrix4f captured=new Matrix4f(root).mul(local);
        check(new Matrix4f(root).invert().mul(captured).equals(local,1e-5f));
        // The AX bone matrix includes the hand/display transform. Only AX chooses it:
        // using a pre-baked fixed transform here would displace an unanimated hand model.
        Matrix4f hand=new Matrix4f().translate(-.6f,.2f,.1f).rotateX(1.2f).scale(.4f,.6f,.7f);
        Matrix4f group=new Matrix4f().translate(.3f,.1f,.2f).rotateZ(.7f);
        Matrix4f actual=new Matrix4f(root).mul(hand).mul(group);
        Matrix4f localCaptured=new Matrix4f(root).invert().mul(actual);
        check(new Matrix4f(root).mul(localCaptured).equals(actual,1e-5f));
        Matrix4f otherRoot=new Matrix4f().translate(-2,5,-9).rotateY(2.4f);
        p.begin(a,4,6);p.capture("crystal",actual);
        Matrix4f saved=new Matrix4f(p.get(0,a,4,6));
        p.begin(b,4,7);p.capture("crystal",new Matrix4f(otherRoot).mul(hand).mul(group));
        check(p.get(0,a,4,6)==null&&saved.equals(actual,1e-5f));
        for(String[] names:new String[][]{{"a","a"},{null},{""}}){boolean rejected=false;try{new AxPose(names);}catch(IllegalArgumentException e){rejected=true;}check(rejected);}
        System.out.println("AxPose: copy isolation, draw/owner/generation isolation, missing/invalid/duplicate pose, zero scale and clear passed");
    }
}
