package com.tyranor.runtime.systemnnn;

import java.util.HashMap;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class DialogueLayoutTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void put(Map<String,String> m,String key,Object value){m.put(key.toLowerCase(Locale.ROOT),value.toString());}
    private static void text(DialogueLayout.Text t,int font,int x,int y,int nx,int ny,int space){
        check(t.font==font&&t.x==x&&t.y==y&&t.nextX==nx&&t.nextY==ny&&t.space==space,"text layout");
    }
    private static Map<String,String> read(Path path)throws Exception{
        Map<String,String> fields=new HashMap<>();
        for(String line:Files.readAllLines(path,StandardCharsets.ISO_8859_1)){
            String[] pair=line.trim().split("\\s+",2);if(pair.length==2)put(fields,pair[0],pair[1]);
        }
        return fields;
    }
    public static void main(String[] args)throws Exception{
        check(PlayerActivity.pointerCoordinate(500,100,800,1600,false)==200,"native panel pointer uses stage origin and scale");
        check(PlayerActivity.pointerCoordinate(99.5f,100,800,1600,false)==-1&&
            PlayerActivity.pointerCoordinate(1700,100,800,1600,false)==800,"native panel keeps either outside edge invalid for hit testing");
        check(PlayerActivity.pointerCoordinate(99.5f,100,800,1600,true)==0&&
            PlayerActivity.pointerCoordinate(1700,100,800,1600,true)==799,"ordinary story keeps its original canvas clamp");
        check(PlayerActivity.pointerCoordinate(Float.MAX_VALUE,Integer.MIN_VALUE,800,1,false)==Integer.MAX_VALUE&&
            PlayerActivity.pointerCoordinate(-Float.MAX_VALUE,Integer.MAX_VALUE,800,1,false)==Integer.MIN_VALUE,
            "extreme native pointer coordinates saturate without overflowing into the canvas");
        Map<String,String> cursor=new HashMap<>();
        check(DialogueLayout.cursorName(cursor,0,0,0).equals("mes_cur"),"cursor default asset");
        put(cursor,"filenameCursor1","base");put(cursor,"filenameCursor2","automatic");
        put(cursor,"cursorNumberScript",3);put(cursor,"cursorNumberConfig",2);
        put(cursor,"filenameCursor1_1_1","first");put(cursor,"filenameCursor1_2_2","script2config2");
        check(DialogueLayout.cursorName(cursor,0,1,1).equals("script2config2"),"cursor script axis precedes config axis");
        check(DialogueLayout.cursorName(cursor,0,2,1).equals("first"),"missing cursor variant inherits the first cell");
        check(DialogueLayout.cursorName(cursor,1,2,1).equals("automatic"),"cursor kind keeps its own fallback");
        put(cursor,"cursorNumberConfig",1);put(cursor,"filenameCursor1_3","script3");
        check(DialogueLayout.cursorName(cursor,0,2,0).equals("script3"),"script-only cursor uses the script axis");
        put(cursor,"cursorNumberScript",1);put(cursor,"cursorNumberConfig",2);put(cursor,"filenameCursor1_2","config2");
        check(DialogueLayout.cursorName(cursor,0,99,1).equals("config2"),"config-only cursor ignores the script axis");
        put(cursor,"cursorNumberConfig",1);
        check(DialogueLayout.cursorName(cursor,0,99,99).equals("base"),"single cursor ignores variant keys");
        for(int s:new int[]{1,3})for(int c:new int[]{1,2}){
            DialogueLayout.Counts n=new DialogueLayout.Counts(c,s,2);
            int j=s-1,i=c-1;
            String suffix=s>1&&c>1?s+"_"+c:Integer.toString(s<=1?c:s);
            check(DialogueLayout.key("filenameWindow",n,j,i,0).equals("filenameWindow"+suffix),"base key");
            check(DialogueLayout.key("filenameNameWindow",n,j,i,1).equals("filenameNameWindow"+suffix+"_Font1"),"font name key");
            check(DialogueLayout.index(n,j,i,1)==s*c+j*c+i,"index");
            check(DialogueLayout.index(n,999,-2,999)==s*c+(s-1)*c,"clamped index");
            Map<String,String> m=new HashMap<>();put(m,"filenameWindow","base");
            put(m,DialogueLayout.key("filenameWindow",n,j,i,1),"variant");
            String[] names=DialogueLayout.names(m,"filenameWindow","ta_meswin",n);
            check(names[DialogueLayout.index(n,j,i,1)].equals("variant"),"variant lookup");
            check(names[DialogueLayout.index(n,j,i,0)].equals("base"),"fallback uses base not unfonted variant");
            check(DialogueLayout.names(new HashMap<>(),"filenameNameWindow","ta_namewin",n)[0].equals("ta_namewin"),"source default");
        }
        Map<String,String> m=new HashMap<>();
        put(m,"fontSizeTypeNumber",4);put(m,"fontSize",30);put(m,"printX",180);put(m,"printY",580);
        put(m,"nextX",0);put(m,"nextY",30);put(m,"space",0);
        int[][] values={{30,150,170,0,42,0},{30,180,580,0,30,0},{30,230,300,0,68,0}};
        String[] keys={"fontSize","printX","printY","nextX","nextY","space"};
        for(int i=0;i<values.length;i++)for(int k=0;k<keys.length;k++)put(m,keys[k]+"ByFontSizeType"+(i+1),values[i][k]);
        DialogueLayout.Text[] layouts=DialogueLayout.layouts(m);check(layouts.length==5,"type count");
        text(layouts[0],30,180,580,0,30,0);text(layouts[1],30,150,170,0,42,0);
        text(layouts[2],30,180,580,0,30,0);text(layouts[3],30,230,300,0,68,0);
        text(layouts[4],30,180,580,0,30,0);text(DialogueLayout.layout(layouts,-1),30,180,580,0,30,0);
        check(DialogueLayout.layout(layouts,99)==layouts[4],"font clamp");
        text(DialogueLayout.layouts(new HashMap<>())[0],24,100,100,0,30,1);
        Map<String,String> names=new HashMap<>();
        put(names,"nameX",80);put(names,"nameY",535);put(names,"nameFontSize",30);put(names,"nameLengthMax",6);
        put(names,"nameSpace",4);put(names,"nameXByFontSizeType1",140);
        put(names,"nameYByFontSizeType1",250);put(names,"nameSpaceByFontSizeType1",7);
        DialogueLayout.Name[] nameLayouts=DialogueLayout.nameLayouts(names,layouts,false);
        check(nameLayouts[0].y==535&&nameLayouts[0].space==1&&nameLayouts[0].printX("ABCD")==111,"base font slot spacing");
        check(nameLayouts[1].y==250&&nameLayouts[1].space==7&&nameLayouts[1].printX("ABCD")==177,"font-specific name coordinates");
        check(nameLayouts[2].space==4&&nameLayouts[2].printX("ABCDEF!") == 80,"long name starts at its anchor");
        put(names,"nameCenterFlag",2);
        check(DialogueLayout.nameLayouts(names,new DialogueLayout.Text[]{layouts[0]},true)[0].printX("ABCD")==118,
            "one-byte name uses half-width cells and right alignment");
        put(names,"nameCenterFlag",0);
        check(DialogueLayout.nameLayouts(names,layouts,false)[0].printX("A")==80,"left-aligned name");
        Set<String> known=Collections.singleton("Known");
        String dialogue="Known\nText";
        DialogueLayout.Message named=DialogueLayout.split(dialogue,true,true,true,known);
        check(named.name.equals("Known")&&named.body.equals("Text")&&named.nameWindow,"known name gets its own text and image");
        DialogueLayout.Message noName=DialogueLayout.split(dialogue,true,false,true,known);
        check(noName.name.isEmpty()&&noName.body.equals(dialogue)&&noName.nameWindow,"nameFlag preserves the first row independently of the image");
        DialogueLayout.Message plain=DialogueLayout.split("Unknown\nText",true,true,true,known);
        check(plain.name.isEmpty()&&plain.body.equals("Unknown\nText")&&!plain.nameWindow,"automatic names require the palette entry");
        DialogueLayout.Message forced=DialogueLayout.split("Unknown\nText",true,true,false,known);
        check(forced.name.equals("Unknown")&&forced.body.equals("Text")&&!forced.nameWindow,"forced name has text without requiring an image");
        DialogueLayout.Message longText=DialogueLayout.split(dialogue,false,true,true,known);
        check(longText.name.isEmpty()&&longText.body.equals(dialogue)&&!longText.nameWindow,"LPRINT keeps the first row");
        check(DialogueLayout.usesNameTable(names),"name color table is enabled by default");
        put(names,"nameColorChangeFlag",0);
        check(!DialogueLayout.usesNameTable(names),"both disabled flags use default name color");
        put(names,"autoNameWindowFlag",1);
        check(DialogueLayout.usesNameTable(names),"automatic name windows still need the name table");
        put(m,"LPrintX",110);put(m,"LPrintY",220);put(m,"LNextX",-13);put(m,"LNextY",36);
        DialogueLayout.Text lprint=DialogueLayout.longLayouts(m,layouts)[1];
        text(lprint,30,110,220,-13,36,0);
        check(DialogueLayout.lineX(lprint,0,1280)==110&&DialogueLayout.lineX(lprint,2,1280)==84&&
            DialogueLayout.lineX(lprint,Integer.MAX_VALUE,1280)==0,"logical rows use LNextX with clamped coordinates");
        put(m,"fontSizeByFontSizeType1",100);check(DialogueLayout.layouts(m)[1].font==72,"upstream font maximum");
        put(m,"windowNumberConfig",1000);put(m,"windowNumberScript",1000);put(m,"windowNumberFontSizeType",1000);
        check(DialogueLayout.counts(m).size()<=DialogueLayout.MAX_VARIANTS,"bounded window table");
        put(m,"fontSizeTypeNumber",1000);check(DialogueLayout.layouts(m).length==DialogueLayout.MAX_LAYOUTS,"bounded layout table");
        DialogueLayout.WindowBand[] single=DialogueLayout.windowBands(640,180,0,60);
        check(single.length==1&&single[0].srcY==0&&single[0].width==640&&single[0].height==180&&single[0].alpha==153,
            "plain translucent window band");
        check(DialogueLayout.windowBands(640,180,0,0).length==0,"zero opacity skips translucent window band");
        DialogueLayout.WindowBand[] borderOnly=DialogueLayout.windowBands(640,180,1,0);
        check(borderOnly.length==1&&borderOnly[0].srcY==180&&borderOnly[0].alpha==255,
            "notTransWindowUse=1 keeps opaque border even at 0% window opacity");
        DialogueLayout.WindowBand[] borderAndBase=DialogueLayout.windowBands(640,180,1,50);
        check(borderAndBase.length==2&&borderAndBase[0].srcY==0&&borderAndBase[0].alpha==128&&
            borderAndBase[1].srcY==180&&borderAndBase[1].alpha==255,"notTransWindowUse=1 draws translucent base then opaque border");
        DialogueLayout.WindowBand[] underlayAndBase=DialogueLayout.windowBands(640,100,2,50);
        check(underlayAndBase.length==2&&underlayAndBase[0].srcY==100&&underlayAndBase[0].alpha==255&&
            underlayAndBase[1].srcY==0&&underlayAndBase[1].alpha==128,"notTransNameWindowUse=2 draws opaque underlay at y=h then base");
        DialogueLayout.WindowBand[] threeBands=DialogueLayout.windowBands(640,180,3,80);
        check(threeBands.length==3&&threeBands[0].srcY==360&&threeBands[0].alpha==255&&
            threeBands[1].srcY==0&&threeBands[1].alpha==204&&
            threeBands[2].srcY==180&&threeBands[2].alpha==255,"notTransWindowUse=3 draws underlay y=2h, base y=0, border y=h");
        check(DialogueLayout.windowBands(0,180,3,100).length==0,"empty window dimensions emit no bands");
        int[] baseKey={3,20,1,0,50,0};
        int[] visibleKey=DialogueLayout.composePlaneKey(baseKey,false,3);
        check(visibleKey.length==6&&visibleKey[0]==3&&visibleKey[4]==50&&visibleKey[5]==3,"composePlaneKey preserves fields 0..4 and choice page");
        int[] hiddenKey=DialogueLayout.composePlaneKey(baseKey,true,-5);
        check((hiddenKey[5]&DialogueLayout.PLANE_UI_HIDDEN)!=0&&(hiddenKey[5]&0xffff)==0,"composePlaneKey sets PLANE_UI_HIDDEN and clamps negative choice page");
        int[] emptyKey=DialogueLayout.composePlaneKey(null,true,0x12345);
        check(emptyKey[0]==0&&emptyKey[5]==(DialogueLayout.PLANE_UI_HIDDEN|0x2345),"composePlaneKey handles null source and masks 16-bit choice page");
        check(DialogueLayout.planeKeyMatches(new int[]{3,20,1,0,50,99},baseKey),"planeKeyMatches ignores choice page in key[5] when visible");
        check(!DialogueLayout.planeKeyMatches(hiddenKey,baseKey),"planeKeyMatches rejects PLANE_UI_HIDDEN plane");
        check(!DialogueLayout.planeKeyMatches(new int[]{3,20,1,0,51,0},baseKey),"planeKeyMatches rejects mismatched choice plane id");
        int[] completeKey=DialogueLayout.composePlaneKey(baseKey,false,3,true);
        check(DialogueLayout.planeKeyMatches(visibleKey,baseKey),"an incomplete text plane remains valid for rendering");
        check(DialogueLayout.evaluatePlaneGate(visibleKey,baseKey,true,true,false,0L)==DialogueLayout.GATE_HOLD,"a partially drawn sentence cannot advance");
        check(DialogueLayout.evaluatePlaneGate(completeKey,baseKey,true,true,false,0L)==DialogueLayout.GATE_MATCHED,"a complete presented sentence can advance");
        check(DialogueLayout.evaluatePlaneGate(visibleKey,baseKey,true,true,false,5000L)==DialogueLayout.GATE_TIMED_OUT,"elapsed time does not acknowledge missing full text");
        int[] hiddenComplete=DialogueLayout.composePlaneKey(baseKey,true,3,true);
        check((hiddenComplete[5]&DialogueLayout.PLANE_TEXT_COMPLETE)==0,"hidden UI cannot acknowledge visible full text");
        int[] choiceKey={4,0,0,0,51,0};
        check(DialogueLayout.evaluatePlaneGate(choiceKey,choiceKey,true,true,false,0L)==DialogueLayout.GATE_MATCHED,"choices require their current plane without a dialogue completion bit");
        check(DialogueLayout.evaluatePlaneGate(hiddenKey,baseKey,true,true,false,100L)==DialogueLayout.GATE_HOLD,"R2 gate holds while UI hidden bit is still presented");
        check(DialogueLayout.evaluatePlaneGate(new int[]{3,20,1,0,0,0},baseKey,true,true,false,249L)==DialogueLayout.GATE_HOLD,"R2 gate holds before 250ms timeout");
        check(DialogueLayout.evaluatePlaneGate(new int[]{3,20,1,0,0,0},baseKey,true,true,false,250L)==DialogueLayout.GATE_TIMED_OUT,"R2 gate reports an unacknowledged plane at 250ms");
        check(DialogueLayout.evaluatePlaneGate(null,new int[]{1,20,1,1,0,0},true,true,false,0L)==DialogueLayout.GATE_MATCHED,"R2 gate never holds non-DIALOGUE/CHOICE waits");
        check(DialogueLayout.evaluatePlaneGate(null,baseKey,false,true,false,0L)==DialogueLayout.GATE_BYPASSED,"R2 gate bypasses when surface is detached");
        check(DialogueLayout.evaluatePlaneGate(null,baseKey,true,false,false,0L)==DialogueLayout.GATE_BYPASSED,"R2 gate bypasses when activity is paused");
        check(DialogueLayout.evaluatePlaneGate(null,baseKey,true,true,true,0L)==DialogueLayout.GATE_BYPASSED,"R2 gate bypasses when menu is open");
        check(DialogueLayout.choiceSetId(null)==0&&DialogueLayout.choiceSetId(new int[]{4,0,0,0})==0,"choiceSetId safely handles null and short keys");
        check(DialogueLayout.choiceSetId(new int[]{4,0,0,0,0x12345678,0})!=DialogueLayout.choiceSetId(new int[]{4,0,0,0,0x12345679,0}),
            "choiceSetId distinguishes repeated SELECT loops at identical script coordinates");
        check(!DialogueLayout.portraitMoving(0,true,4,true,12,-12)&&
            !DialogueLayout.portraitMoving(0,true,4,true,12,-1),"portrait waits for its deadline instead of redrawing while idle");
        check(DialogueLayout.portraitMoving(0,true,4,false,12,0),"blinking primary portrait stays active across wait cycles");
        check(DialogueLayout.portraitMoving(0,true,4,true,0,0),"finite one-shot portrait animates before end frame");
        check(!DialogueLayout.portraitMoving(0,true,4,false,0,0),"finite one-shot portrait converges once finished");
        check(!DialogueLayout.portraitMoving(1,true,4,true,12,0),"secondary composite portrait layer fixed at frame 0 never loops");
        check(!DialogueLayout.portraitMoving(0,false,4,true,12,0)&&!DialogueLayout.portraitMoving(0,true,1,true,12,0),
            "missing or single-frame portrait never animates");
        check(DialogueLayout.letterSpacing(-12,24)==0f&&DialogueLayout.letterSpacing(-1,24)==0f&&
            DialogueLayout.letterSpacing(0,24)==0f,"negative or zero space does not squish proportional Android glyphs");
        check(DialogueLayout.letterSpacing(2,0)==0f&&DialogueLayout.letterSpacing(2,-10)==0f,
            "non-positive font size produces zero letter spacing");
        check(Math.abs(DialogueLayout.letterSpacing(1,24)-(1f/24f))<1e-6f&&
            Math.abs(DialogueLayout.letterSpacing(6,24)-0.25f)<1e-6f,"positive space preserves configured extra tracking");
        check(DialogueLayout.letterSpacing(100,24)==1f,"extreme positive space clamps to one em");
        Map<String,String> neg=new HashMap<>();put(neg,"fontSize",0);put(neg,"space",-10);
        DialogueLayout.Text[] negLayouts=DialogueLayout.layouts(neg);
        check(negLayouts[0].font==1&&negLayouts[0].space==-10&&
            DialogueLayout.letterSpacing(negLayouts[0].space,negLayouts[0].font)==0f,
            "layout clamps font minimum while negative space yields zero letter spacing");
        DialogueLayout.Text[] negLong=DialogueLayout.longLayouts(neg,negLayouts);
        check(negLong[0].font==1&&negLong[0].space==-10&&
            DialogueLayout.letterSpacing(negLong[0].space,negLong[0].font)==0f,
            "LPRINT layout preserves negative space metadata while rendering zero letter spacing");
        if(args.length>0){
            Path root=Paths.get(args[0]);Map<String,String> real=read(root.resolve("init/printmessage.xtx"));
            DialogueLayout.Text[] actual=DialogueLayout.layouts(real);
            check(actual.length==5,"DEA font count");text(actual[0],30,180,580,0,30,0);
            text(actual[1],30,150,170,0,42,0);text(actual[2],30,180,580,0,30,0);
            text(actual[3],30,230,300,0,68,0);text(actual[4],30,180,580,0,30,0);
            Map<String,String> windows=read(root.resolve("init/messageWindow.xtx"));DialogueLayout.Counts n=DialogueLayout.counts(windows);
            check(n.config==1&&n.script==4&&n.font==1,"DEA window counts");
            String[] realNames=DialogueLayout.names(windows,"filenameWindow","ta_meswin",n);
            check(realNames[0].equals("ta_meswin01")&&realNames[1].equals("ta_meswin02")&&
                realNames[2].equals("ta_meswin02")&&realNames[3].equals("ta_meswin02"),"DEA window names");
            String[] realNameWindows=DialogueLayout.names(windows,"filenameNameWindow","ta_namewin",n);
            check(realNameWindows[0].equals("ta_meswin_name01")&&realNameWindows[1].equals("ta_meswin_name02"),"DEA name windows");
        }
        System.out.println("PASS "+checks+" dialogue layout checks");
    }
}
