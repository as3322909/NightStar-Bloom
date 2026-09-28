package com.nightstar.bloom;

import com.google.gson.*;
import java.io.IOException;

/** Manifest v4 bone identities. Animation playback belongs exclusively to AX. */
public final class AxAnimation {
    private AxAnimation(){}
    static final class Rig {
        final String id;
        final AxPose pose;
        final int[] firsts=new int[2048];
        final String[] names;
        private Rig(String id,String[] names){this.id=id;this.names=names;pose=new AxPose(names);}
        static Rig parse(String id,JsonObject o)throws IOException{
            JsonArray a=o.getAsJsonArray("bones");
            if(a==null||a.isEmpty()||a.size()>4096)throw new IOException("rig "+id+": invalid bones");
            String[] names=new String[a.size()];
            for(int i=0;i<a.size();i++){
                JsonObject b=a.get(i).getAsJsonObject();
                names[i]=b.has("name")?b.get("name").getAsString():null;
                int parent=b.get("parent").getAsInt();
                if(parent < -1||parent>=i)throw new IOException("rig "+id+": invalid parent");
            }
            try{return new Rig(id,names);}catch(IllegalArgumentException e){throw new IOException("rig "+id+": "+e.getMessage(),e);}
        }
    }
}
