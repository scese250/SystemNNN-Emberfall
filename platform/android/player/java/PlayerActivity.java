package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.util.TypedValue;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PlayerActivity extends Activity implements GameRunner.Listener {
    private static final String PREFIX="com.tyranor.runtime.extra.";
    private File game;
    private GameRunner runner;
    private FrameLayout root,stage;
    private TextureView screen;
    private Surface storySurface;
    private Button panelActionButton;
    private final StoryPlane storyPlane=new StoryPlane();
    private DialoguePanel dialoguePanel;
    private GameStoryControls controls;
    private FrameLayout independentStatus;
    private final GameStatusPartyPanel[] independentParts=new GameStatusPartyPanel[2];
    private int independentMode;
    private GameNotice quickNoticeView;
    private DialogueStyle dialogueStyle=DialogueStyle.fallback();
    private GameChoicePanel choices;
    private TitlePanel titlePanel;
    private LogoPanel logoPanel;
    private OpeningScreen startupOpening;
    private boolean launchingGame;
    private TitleMenu titleModel;
    private GameUiAssets uiAssets;
    private GameUiPanel gamePanel,preparingPanel;
    private UiPreviewPool.Warmup panelWarmup;
    private boolean returningToTitle;
    private Bitmap titlePrevious;
    private SceneEntrance sceneEntrance;
    private MoviePlayback moviePlayback;
    private boolean storyMovieExitActive;
    private GameSlotsPanel gameSlots;
    private GalleryCatalog.Page galleryPage;
    private long galleryRequest;
    private int galleryCgPage,settingsPage;
    private boolean sceneReplay;
    private Runnable panelBack;
    private boolean fromTyn;
    private int width=1280,height=720,lastX=0,lastY=0;
    private final int[] storyOrigin=new int[2];
    private float storyDownX,storyDownY;
    private int storyTouchSlop;
    private boolean storyDrag;
    private boolean foreground,permissionPending;
    private static final int REQUEST_FONT_FILE=33;
    private LauncherView launcherView;
    private boolean showingLauncher;
    private AlertDialog storageGateDialog;
    private String activeProfileId;
    private String pendingFontProfileId;
    private File pendingImportSourceDir;
    private Runnable refreshReadingSettingsFont;
    private Uri selectedGameTree;
    private boolean copyingGame,usingGameCopy;
    private volatile boolean cancelGameCopy;
    private boolean uiHidden,autoMode,skipMode,typing,refreshAfterLoad;
    private int panelsOpen,revealed;
    private String fullBody="";
    private GameRunner.Frame currentFrame;
    private GameRunner.Modal visibleModal,modeUnderPanel;
    private Bitmap optionalGalleryPrevious;
    private boolean openGalleryAfterTitle;
    private SharedPreferences preferences,userPreferences;
    private PlayerSettings settings;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ArrayList<AlertDialog> openedPanels=new ArrayList<>();
    private AlertDialog slotsDialog;
    private LinearLayout slotRows;
    private boolean savingSlots;
    private boolean syncingRuntime;
    private int[] pendingDisplay,pendingAuxiliary;
    private int mouseVariant=Integer.MIN_VALUE,scriptMouse,ctrlKeys;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener=(p,key) -> {
        if(syncingRuntime||p!=preferences)return;
        settings=PlayerSettings.read(p,userPreferences);
        if (dialoguePanel!=null) dialoguePanel.settings(settings);
        selectDialogueVariant();
        if (runner!=null) runner.settings(key,settings);
        immersive();
        updateStatus();
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener readingListener=(p,key)->{
        if(PlayerSettings.CUSTOM_FONT_VERSION.equals(key)){
            if(dialoguePanel!=null){dialoguePanel.refreshFont();storyPlane.schedulePush();}
            if(gamePanel!=null)gamePanel.postInvalidate();
            return;
        }
        if(preferences==null||!PlayerSettings.READING_SIZE.equals(key))return;
        settings=PlayerSettings.read(preferences,p);
        if(dialoguePanel!=null){dialoguePanel.settings(settings);storyPlane.schedulePush();}
    };

    private void pickFontFile() {
        pickFontFileForProfile(activeProfileId);
    }

    private void pickFontFileForProfile(String profileId) {
        pendingFontProfileId=(profileId!=null&&!profileId.isEmpty())?profileId:PlayerFont.activeProfileId();
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
            "font/ttf","font/otf","application/x-font-ttf","application/x-font-opentype","application/octet-stream"
        });
        startActivityForResult(intent,REQUEST_FONT_FILE);
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(Build.VERSION.SDK_INT>=28){
            WindowManager.LayoutParams window=getWindow().getAttributes();
            window.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(window);
        }
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        root=new FrameLayout(this); root.setBackgroundColor(Color.BLACK); setContentView(root);
        immersive();
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(vis->{
            if((vis&(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION))!=
                (View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)){
                immersive();
            }
        });
        root.post(this::immersive);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            int left=0,top=0,right=0,bottom=0;
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.displayCutout());
                left=safe.left;top=safe.top;right=safe.right;bottom=safe.bottom;
            }else if(Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null){
                DisplayCutout cutout=insets.getDisplayCutout();
                left=cutout.getSafeInsetLeft();top=cutout.getSafeInsetTop();
                right=cutout.getSafeInsetRight();bottom=cutout.getSafeInsetBottom();
            }
            if(view.getPaddingLeft()!=left||view.getPaddingTop()!=top||view.getPaddingRight()!=right||view.getPaddingBottom()!=bottom){
                view.setPadding(left,top,right,bottom);layoutStage();
            }
            return insets;
        });
        root.requestApplyInsets();
        userPreferences=PlayerSettings.preferences(this);
        userPreferences.registerOnSharedPreferenceChangeListener(readingListener);
        Intent i=getIntent();
        String path=i.getStringExtra(PREFIX+"FOLDER_PATH");
        fromTyn="com.tyranor.runtime.action.LAUNCH".equals(i.getAction());
        if (!fromTyn) {
            if (saved!=null){
                path=saved.getString("selectedGame");
                String tree=saved.getString("selectedGameTree");
                if(tree!=null)selectedGameTree=Uri.parse(tree);
                String savedProfileId=saved.getString("activeProfileId");
                if(savedProfileId!=null&&!savedProfileId.isEmpty()){
                    activeProfileId=savedProfileId;
                    PlayerFont.setActiveProfile(activeProfileId);
                }
            }
            if (path==null || path.isEmpty()) { chooseHome(null); return; }
        } else if (path==null || path.isEmpty()) {
            message("Unable to Open Game","TYN did not provide a game folder.",null,null); return;
        }
        if (i.getIntExtra(PREFIX+"PROTOCOL_VERSION",1)!=1) {
            message("Unsupported Launch Protocol","Please check Tyranor-Next and engine module versions.",null,null); return;
        }
        try {
            File input=new File(path);
            if (!input.isAbsolute()) throw new IOException("The game directory passed by TYN must be an absolute path");
            game=input.getCanonicalFile();
            if(activeProfileId==null){
                activeProfileId=GameProfileStore.profileIdForPath(game.getPath());
            }
            PlayerFont.setActiveProfile(activeProfileId);
        } catch (IOException e) { message("Unable to Open Game",e.getMessage(),null,null); return; }
        inspectAccess();
    }
    private boolean hasRequiredStandaloneStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT == 29 && !Environment.isExternalStorageLegacy()) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    private void enforceStandaloneStorageGate() {
        if (fromTyn || runner != null || isFinishing() || isDestroyed()) {
            return;
        }
        if (hasRequiredStandaloneStoragePermission()) {
            if (storageGateDialog != null && storageGateDialog.isShowing()) {
                storageGateDialog.dismiss();
            }
            storageGateDialog = null;
            if (launcherView != null) {
                launcherView.refreshIconsAfterPermissionGranted();
            }
            return;
        }
        if (storageGateDialog != null && storageGateDialog.isShowing()) {
            return;
        }
        showStoragePermissionGate();
    }
    private void showStoragePermissionGate() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(EmberfallUi.folderLockIcon(this, 40, EmberfallUi.ACCENT));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
            EmberfallUi.dp(this, 40),
            EmberfallUi.dp(this, 40)
        );
        iconLp.setMarginEnd(EmberfallUi.dp(this, 12));
        headerRow.addView(icon, iconLp);

        TextView title = EmberfallUi.text(this, "Grant Storage Access", 17f, EmberfallUi.TEXT_PRIMARY, true);
        headerRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(headerRow);

        TextView body = EmberfallUi.text(
            this,
            "Storage access is required to load game files and save your progress.",
            13.5f,
            EmberfallUi.TEXT_SECONDARY,
            false
        );
        body.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bodyLp.topMargin = EmberfallUi.dp(this, 12);
        content.addView(body, bodyLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actionsLp.topMargin = EmberfallUi.dp(this, 18);

        TextView exitBtn = EmberfallUi.secondaryButton(this, "Exit", null, v -> finish());
        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        exitLp.setMarginEnd(EmberfallUi.dp(this, 10));
        actions.addView(exitBtn, exitLp);

        TextView grantBtn = EmberfallUi.primaryButton(this, "Grant Access", null, v -> openStoragePermissionSettings());
        actions.addView(grantBtn);

        content.addView(actions, actionsLp);

        storageGateDialog = EmberfallUi.createModalDialog(this, content, false, 440);
        storageGateDialog.show();
    }
    private void openStoragePermissionSettings() {
        permissionPending = true;
        if (Build.VERSION.SDK_INT >= 30) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            try {
                startActivity(settings);
            } catch (android.content.ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 21);
        }
    }
    private void chooseHome(String reason) {
        if (fromTyn) {
            message("Unable to Open Game", reason != null ? reason : "TYN did not provide a valid game folder.", null, null);
            return;
        }
        if (launcherView != null) {
            launcherView.destroy();
            launcherView = null;
        }
        root.removeAllViews();
        showingLauncher = true;
        game = null;
        selectedGameTree = null;
        activeProfileId = null;
        pendingImportSourceDir = null;
        PlayerFont.setActiveProfile(null);

        launcherView = new LauncherView(this, new LauncherView.Listener() {
            @Override public void onAddGame() {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 32);
            }
            @Override public void onLaunchGame(GameProfileStore.Profile profile) {
                launchGameProfile(profile);
            }
            @Override public void onPickFontForProfile(String profileId) {
                pickFontFileForProfile(profileId);
            }
            @Override public void onExit() {
                finish();
            }
            @Override public boolean canExtractIcons() {
                return hasRequiredStandaloneStoragePermission();
            }
        });
        root.addView(launcherView, new FrameLayout.LayoutParams(-1, -1));
        if (reason != null && !reason.trim().isEmpty()) {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
        }
        enforceStandaloneStorageGate();
    }
    private void launchGameProfile(GameProfileStore.Profile profile) {
        if (profile == null || profile.folderPath == null || profile.folderPath.isEmpty()) {
            return;
        }
        if (!hasRequiredStandaloneStoragePermission()) {
            enforceStandaloneStorageGate();
            return;
        }
        try {
            File target = new File(profile.folderPath).getCanonicalFile();
            activeProfileId = profile.id;
            PlayerFont.setActiveProfile(profile.id);
            GameProfileStore.touchLastPlayed(this, profile.id);
            game = target;
            selectedGameTree = (profile.treeUri != null && !profile.treeUri.isEmpty()) ? Uri.parse(profile.treeUri) : null;
            pendingImportSourceDir = target;
            inspectAccess();
        } catch (IOException e) {
            Toast.makeText(this, e.getMessage() != null ? e.getMessage() : "Unable to open game folder", Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==REQUEST_FONT_FILE){
            String targetProfileId=pendingFontProfileId!=null?pendingFontProfileId:activeProfileId;
            pendingFontProfileId=null;
            if(result==RESULT_OK&&data!=null&&data.getData()!=null){
                try{
                    String fontName=PlayerFont.importFont(this,targetProfileId,data.getData());
                    Toast.makeText(this,"Loaded font: "+fontName,Toast.LENGTH_SHORT).show();
                    if(runner==null){
                        if(launcherView!=null){
                            launcherView.refreshProfileModalFont(targetProfileId);
                        }else{
                            chooseHome(null);
                        }
                    }else{
                        if(dialoguePanel!=null){
                            dialoguePanel.refreshFont();
                            storyPlane.schedulePush();
                        }
                        if(gamePanel!=null)gamePanel.postInvalidate();
                        if(refreshReadingSettingsFont!=null){
                            refreshReadingSettingsFont.run();
                        }
                    }
                }catch(Exception e){
                    Toast.makeText(this,e.getMessage()!=null?e.getMessage():"Failed to load font",Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        if (request!=32 || result!=RESULT_OK || data==null || data.getData()==null) return;
        try {
            Uri uri=data.getData();
            if (!"com.android.externalstorage.documents".equals(uri.getAuthority()))
                throw new IOException("Please select a folder under 'This Device' or SD Card in the file picker.");
            String[] id=DocumentsContract.getTreeDocumentId(uri).split(":",2);
            if (id.length!=2) throw new IOException("Unable to determine location of the selected folder.");
            File volume=null;
            if ("primary".equalsIgnoreCase(id[0])) volume=Environment.getExternalStorageDirectory();
            else for (StorageVolume v:getSystemService(StorageManager.class).getStorageVolumes())
                if (id[0].equalsIgnoreCase(v.getUuid())) {
                    volume=Build.VERSION.SDK_INT>=30?v.getDirectory():new File("/storage",v.getUuid()); break;
                }
            if (volume==null) throw new IOException("Selected storage volume not found.");
            File selected=new File(volume,id[1]).getCanonicalFile();
            if (!selected.toPath().startsWith(volume.getCanonicalFile().toPath())) throw new IOException("Invalid game folder.");
            if(Build.VERSION.SDK_INT==29)try{
                getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }catch(SecurityException ignored){}

            boolean readable=false;
            File script=GameResourcePaths.file(selected,"spt","sys.spt");
            if(script!=null){
                try(FileInputStream probe=new FileInputStream(script)){
                    File mapping=GameResourcePaths.file(selected,"sjis_ext.bin");
                    if(mapping!=null)try(FileInputStream map=new FileInputStream(mapping)){map.read();}
                    readable=probe.read()!=-1;
                }catch(IOException ignored){}
            }
            if(readable){
                GameProfileStore.Profile profile=GameProfileStore.addOrUpdate(this,selected,uri.toString());
                if(launcherView!=null){
                    launcherView.refresh();
                }else{
                    chooseHome(null);
                }
                if(profile!=null){
                    Toast.makeText(this,"Added "+profile.name,Toast.LENGTH_SHORT).show();
                }
                return;
            }
            if(Build.VERSION.SDK_INT==29&&!Environment.isExternalStorageLegacy()){
                File existingCopy=SystemNnnGameImport.committedRoot(this,SystemNnnGameImport.sourceIdentity(uri));
                if(existingCopy!=null&&SystemNnnGameImport.readableBootstrap(existingCopy)){
                    GameProfileStore.Profile profile=GameProfileStore.addOrUpdate(this,selected,uri.toString());
                    if(profile!=null){
                        File iconOut=GameProfileStore.iconFile(this,profile.id);
                        if(iconOut!=null&&(!iconOut.isFile()||iconOut.length()==0)){
                            final File copyDir=existingCopy;
                            final File dest=iconOut;
                            new Thread(()->{
                                if(ExeIconExtractor.extractToPng(copyDir,dest)){
                                    ui.post(()->{if(launcherView!=null)launcherView.refresh();});
                                }
                            },"Emberfall-AddIcon").start();
                        }
                        Toast.makeText(this,"Added "+profile.name,Toast.LENGTH_SHORT).show();
                    }
                    if(launcherView!=null){
                        launcherView.refresh();
                    }else{
                        chooseHome(null);
                    }
                    return;
                }
                pendingImportSourceDir=selected;
                selectedGameTree=uri;
                game=selected;
                inspectAccess();
                return;
            }
            Toast.makeText(this,"No spt/sys.spt found in selected folder",Toast.LENGTH_SHORT).show();
        } catch (IOException | IllegalArgumentException e) {
            Toast.makeText(this,e.getMessage()!=null?e.getMessage():"Unable to add game folder",Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (!fromTyn && game!=null) state.putString("selectedGame",game.getPath());
        if (!fromTyn && selectedGameTree!=null)state.putString("selectedGameTree",selectedGameTree.toString());
        if (!fromTyn && activeProfileId!=null)state.putString("activeProfileId",activeProfileId);
    }
    private void inspectAccess() {
        if (game==null || runner!=null || copyingGame) return;
        try{
            String identity=sourceIdentity();
            File copy=identity==null?null:SystemNnnGameImport.committedRoot(this,identity);
            usingGameCopy=copy!=null;
            if(usingGameCopy)game=copy;
        }catch(IOException e){chooseHome(gameCopyFailure(e));return;}
        File script=GameResourcePaths.file(game,"spt","sys.spt");
        if (script!=null) {
            // Scoped storage may allow stat() and directory listings while denying open().
            try (FileInputStream probe=new FileInputStream(script)) {
                File mapping=GameResourcePaths.file(game,"sjis_ext.bin");
                if (mapping!=null) try (FileInputStream map=new FileInputStream(mapping)) { map.read(); }
                if (probe.read()!=-1) { startGame(); return; }
            } catch (IOException ignored) { /* Show the appropriate storage action below. */ }
        }
        if(!requestGameAccess(false))chooseHome("No readable spt/sys.spt found in the selected folder.");
    }
    private String sourceIdentity() throws IOException {
        if(selectedGameTree!=null)return SystemNnnGameImport.sourceIdentity(selectedGameTree);
        if(!fromTyn)return null;
        // The external-module launch protocol supplies an absolute path instead of a tree URI.
        java.nio.file.Path source=game.toPath();
        java.nio.file.Path primary=Environment.getExternalStorageDirectory().getCanonicalFile().toPath();
        if(source.startsWith(primary))return SystemNnnGameImport.normalizeIdentity("primary:"+primary.relativize(source));
        java.nio.file.Path storage=new File("/storage").toPath();
        if(!source.startsWith(storage)||source.getNameCount()<2)return null;
        String volume=source.getName(1).toString();
        if("emulated".equals(volume)||"self".equals(volume))return null;
        return SystemNnnGameImport.normalizeIdentity(volume+":"+storage.resolve(volume).relativize(source));
    }
    private boolean requestGameAccess(boolean writing){
        if(Build.VERSION.SDK_INT==29&&selectedGameTree!=null&&!usingGameCopy&&!Environment.isExternalStorageLegacy()){
            message("Copy Game","Direct access to the selected folder is unavailable. You can copy the game into the app directory and launch it; saves will be stored in the copy's sav directory.",
                "Copy to App Storage",v->copySelectedGame());
            return true;
        }
        return requestStorageAccess(writing);
    }
    private void copySelectedGame(){
        if(Build.VERSION.SDK_INT!=29||Environment.isExternalStorageLegacy()||copyingGame||selectedGameTree==null)return;
        Uri tree=selectedGameTree;
        copyingGame=true;
        cancelGameCopy=false;
        message("Copying Game","Copying game files. Time required depends on game size.","Cancel Copy",v->cancelGameCopy=true);
        new Thread(()->{
            File copy=null;
            String failure=null;
            try{
                copy=SystemNnnGameImport.importDirectory(this,tree,SystemNnnGameImport.sourceIdentity(tree),()->{
                    if(cancelGameCopy)throw new java.util.concurrent.CancellationException();
                });
            }catch(java.util.concurrent.CancellationException e){failure="Game copy canceled";}
            catch(IOException e){failure=gameCopyFailure(e);}
            File imported=copy;
            String reason=failure;
            ui.post(()->{
                if(isDestroyed()||isFinishing())return;
                copyingGame=false;
                if(imported==null){pendingImportSourceDir=null;chooseHome(reason);return;}
                if(!fromTyn){
                    File profileDir=pendingImportSourceDir;
                    if(profileDir==null){
                        try{profileDir=SystemNnnGameImport.originalGame(this,imported);}
                        catch(IOException ignored){profileDir=imported;}
                    }
                    pendingImportSourceDir=null;
                    GameProfileStore.Profile profile=GameProfileStore.addOrUpdate(this,profileDir,tree.toString());
                    if(profile!=null){
                        activeProfileId=profile.id;
                        PlayerFont.setActiveProfile(profile.id);
                        GameProfileStore.touchLastPlayed(this,profile.id);
                        File iconOut=GameProfileStore.iconFile(this,profile.id);
                        if(iconOut!=null&&(!iconOut.isFile()||iconOut.length()==0)){
                            final File iconSource=imported;
                            final File iconDest=iconOut;
                            new Thread(()->ExeIconExtractor.extractToPng(iconSource,iconDest),"Emberfall-ImportIcon").start();
                        }
                    }
                }
                game=imported;
                usingGameCopy=true;
                startGame();
            });
        },"SystemNNN-Import").start();
    }
    private String gameCopyFailure(IOException error){
        if(error instanceof SystemNnnGameImport.Failure){
            switch(((SystemNnnGameImport.Failure)error).reason){
                case NO_GRANT:return "Folder access permission has expired; please select the game folder again.";
                case SOURCE_MISSING:case INVALID_SOURCE:return "The selected folder is missing readable game files; please verify the game is fully extracted.";
                case INCOMPLETE_SOURCE:return "Save files in the original folder are still being written or migrated; please close the game and try again.";
                case NO_SPACE:return "Insufficient storage space to copy the game.";
                case BUSY:return "This game is currently being copied; please try again shortly.";
                case CORRUPT_COPY:return "The existing game copy is unavailable; please inspect game files in the app storage.";
            }
        }
        android.util.Log.w("SystemNNN","Could not import the selected game",error);
        return "Unable to copy game; please check storage and folder permissions.";
    }
    private boolean requestStorageAccess(boolean writing) {
        String permission=writing?Manifest.permission.WRITE_EXTERNAL_STORAGE:Manifest.permission.READ_EXTERNAL_STORAGE;
        boolean needsGrant=Build.VERSION.SDK_INT>=30 ? !Environment.isExternalStorageManager() :
                checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED;
        if (needsGrant) {
            if (!fromTyn) {
                chooseHome(null);
            } else {
                message("Grant Storage Access","Please grant file access permissions to SystemNNN to read game resources and write to the sav save directory.",
                    "Open Settings",v -> openStoragePermissionSettings());
            }
        }
        return needsGrant;
    }
    private void startGame() {
        GameSavePaths saves;
        File legacySource;
        try{
            saves=GameSavePaths.forGame(game);
            legacySource=SystemNnnGameImport.originalGame(this,game);
        }
        catch(IOException|SecurityException e){message("Unable to Open Save Directory",e.getMessage(),"Retry",v->inspectAccess());return;}
        File directory=saves.directory();
        if(!(directory.isDirectory()?directory:game).canWrite() && requestGameAccess(true))return;
        storyPlane.release();
        if(launcherView!=null){launcherView.destroy();launcherView=null;}
        showingLauncher=false;
        root.removeAllViews();
        storyTouchSlop=ViewConfiguration.get(this).getScaledTouchSlop();
        stage=new FrameLayout(this){
            @Override public void onDescendantInvalidated(View child,View target){
                super.onDescendantInvalidated(child,target);
                if(child==dialoguePanel||child==choices)storyPlane.schedulePush();
            }
        };
        stage.getViewTreeObserver().addOnPreDrawListener(()->{storyPlane.onPreDraw();return true;});
        launchingGame=true;
        settingsPage=0;
        stage.setForeground(new ColorDrawable(Color.BLACK));
        root.addView(stage,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        panelActionButton=new Button(this);
        panelActionButton.setSingleLine(true);
        panelActionButton.setVisibility(View.GONE);
        panelActionButton.setOnClickListener(v->{
            if(!panelActionReady())return;
            if(gamePanel instanceof GameSettingsPanel)showReadingSettings();
        });
        FrameLayout.LayoutParams actionPosition=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);
        actionPosition.setMargins(dp(10),dp(10),dp(10),0);
        root.addView(panelActionButton,actionPosition);
        EmberfallUi.styleFloatingActionButton(panelActionButton);
        panelActionButton.setMinHeight(dp(38));
        panelActionButton.setMinimumHeight(dp(38));
        root.getViewTreeObserver().addOnPreDrawListener(()->{updatePanelActionEnabled();return true;});
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> layoutStage());
        screen=new TextureView(this); screen.setContentDescription("Game Screen");screen.setSoundEffectsEnabled(false);
        stage.addView(screen,new FrameLayout.LayoutParams(-1,-1));
        screen.setOnTouchListener((view,event) -> {
            if (event.getActionMasked()==MotionEvent.ACTION_UP) {advance();view.performClick();}
            return true;
        });
        dialoguePanel=new DialoguePanel(this,v -> advance());
        dialoguePanel.planeInvalidated(storyPlane::schedulePush);
        stage.addView(dialoguePanel,new FrameLayout.LayoutParams(-1,-1)); dialoguePanel.setVisibility(View.GONE);
        preferences=PlayerSettings.displayPreferences(this,legacySource);
        settings=PlayerSettings.read(preferences,userPreferences);
        dialoguePanel.settings(settings);
        preferences.registerOnSharedPreferenceChangeListener(settingsListener);
        runner=new GameRunner(getApplicationContext(),game,legacySource,saves,getFilesDir(),preferences,userPreferences,settings,this);
        GameRunner surfaceOwner=runner;
        screen.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            private Surface attached;
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){
                storyPlane.textureReset();
                attached=new Surface(texture);
                storySurface=attached;
                surfaceOwner.surface(attached);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){
                // Only the viewport changed; retain the producer and its logical game buffers.
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){
                Surface old=attached;
                attached=null;
                if(storySurface==old){storySurface=null;storyPlane.textureReset();}
                surfaceOwner.surface(null);
                // Abandon the consumer after TextureView detaches it. A blocked producer wakes
                // without making the UI thread wait for the current native buffer submission.
                new Handler(Looper.getMainLooper()).post(()->{
                    try{texture.release();}
                    finally{if(old!=null)old.release();}
                });
                return false;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){
                if(runner==surfaceOwner&&attached!=null&&storySurface==attached)
                    storyPlane.textureUpdated(texture.getTimestamp());
            }
        });
        if (foreground) runner.active(true);
        layoutStage();
    }
    private void systemClick() {if(runner!=null)runner.playClick();}
    private View.OnClickListener clicked(View.OnClickListener action) {return v -> {systemClick();if(action!=null)action.onClick(v);};}
    private DialogInterface.OnClickListener dialogClick(DialogInterface.OnClickListener action) {
        return (dialog,which) -> {systemClick();if(action!=null)action.onClick(dialog,which);};
    }
    private void scaleLogicalView(View view,int stageW,int stageH){
        if(view==null)return;
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)view.getLayoutParams();
        if(params.width!=width||params.height!=height){
            params.width=width;params.height=height;
            view.setLayoutParams(params);
        }
        view.setPivotX(0);
        view.setPivotY(0);
        view.setScaleX(stageW/(float)width);
        view.setScaleY(stageH/(float)height);
    }
    private void layoutStage() {
        if(stage==null)return;
        storyPlane.schedulePush();
        if(dialoguePanel!=null)dialoguePanel.canvas(width,height);
        if(controls!=null)controls.canvas(width,height);
        if(quickNoticeView!=null)quickNoticeView.canvas(width,height);
        if(gamePanel!=null)gamePanel.canvas(width,height);
        reserveCommandSpace();
        if(root.getWidth()==0 || root.getHeight()==0)return;
        int availableWidth=root.getWidth()-root.getPaddingLeft()-root.getPaddingRight();
        int availableHeight=root.getHeight()-root.getPaddingTop()-root.getPaddingBottom();
        if(availableWidth<=0||availableHeight<=0)return;
        float scale=Math.min((float)availableWidth/width,(float)availableHeight/height);
        int w=Math.max(1,Math.round(width*scale)),h=Math.max(1,Math.round(height*scale));
        scaleLogicalView(dialoguePanel,w,h);
        scaleLogicalView(choices,w,h);
        int left=(availableWidth-w)/2;
        int top=(availableHeight-h)/2;
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)stage.getLayoutParams();
        int gravity=Gravity.TOP|Gravity.LEFT;
        if(params.width!=w||params.height!=h||params.gravity!=gravity||params.leftMargin!=left||params.topMargin!=top){
            params.width=w;params.height=h;params.gravity=gravity;params.leftMargin=left;params.topMargin=top;
            stage.setLayoutParams(params);
        }
    }
    @Override public void loaded() {runOnUiThread(() -> {
        refreshAfterLoad=true;modeUnderPanel=null;
        if(quickNoticeView!=null)quickNoticeView.clear();
    });}
    @Override public void frame(GameRunner.Frame frame,int initialVisible) {
        runOnUiThread(()->applyFrame(frame,initialVisible));
    }
    @Override public void planeChanged(){runOnUiThread(storyPlane::onTokenAvailable);}
    @Override public void nativePanelMenu(GameRunner owner,GameRunner.Frame frame,int mode){runOnUiThread(()->{
        if(owner!=runner||currentFrame!=frame||isDestroyed()||isFinishing())return;
        try{
            if(uiAssets==null)throw new IllegalStateException("Native panel menu resources are unavailable");
            switch(mode){
                case 5:showSlots(false);break;
                case 6:showSlots(true);break;
                case 12:
                    if(!uiAssets.config.has("modename")&&!uiAssets.settingsModel(width,height).original)
                        throw new IllegalStateException("Original configuration menu is unavailable");
                    showSettings();break;
                case 13:
                    if(!uiAssets.system.has("Menu1Button1FileName"))throw new IllegalStateException("Original system menu is unavailable");
                    showOriginalMenu();break;
                default:throw new IllegalStateException("Unknown native panel menu request");
            }
        }catch(RuntimeException|OutOfMemoryError error){owner.storyPlaneFailed(frame.generation,error);}
    });}
    private void applyFrame(GameRunner.Frame frame,int initialVisible){
        if (isDestroyed() || stage==null) return;
        if (titlePanel!=null) {stage.removeView(titlePanel); titlePanel=null; titleModel=null;}
        clearStartupForStory();
        if(storyMovieExitActive){storyMovieExitActive=false;stage.setForeground(null);}
        if (frame.width>0&&frame.height>0&&(width!=frame.width || height!=frame.height)) {width=frame.width;height=frame.height;layoutStage();}
        if(frame.wait==NativeGame.MODAL){
            currentFrame=frame;
            // Preserve the last story composition until the modal has captured it.
            if(gamePanel==null&&preparingPanel==null)showScriptModal(null);
            return;
        }
        modeUnderPanel=null;
        if(frame.wait==NativeGame.NATIVE_PANEL){
            currentFrame=frame;typing=false;fullBody="";uiHidden=false;refreshAfterLoad=false;
            clearCtrlSkip();storyPlane.frame(null);
            if(choices!=null){stage.removeView(choices);choices=null;}
            dialoguePanel.setVisibility(View.GONE);
            if(controls!=null)controls.setVisibility(View.GONE);
            updateVisibility();updateStatus();return;
        }
        boolean changed=refreshAfterLoad || currentFrame==null || currentFrame.wait!=frame.wait || currentFrame.line!=frame.line ||
            currentFrame.word!=frame.word || !currentFrame.script.equals(frame.script) ||
            !currentFrame.body.equals(frame.body) || !currentFrame.speaker.equals(frame.speaker) ||
            currentFrame.nameWindow!=frame.nameWindow || currentFrame.decorations!=frame.decorations ||
            DialogueLayout.choiceSetId(currentFrame.planeKey)!=DialogueLayout.choiceSetId(frame.planeKey);
        currentFrame=frame;
        storyPlane.frame(frame);
        dialoguePanel.directDraw(frame.wait==NativeGame.END);
        dialoguePanel.scriptCursorVariant(frame.scriptCursor);
        dialoguePanel.storyBody(frame.hasDialogueText());
        dialoguePanel.passive(frame.wait==NativeGame.DRAW);
        dialoguePanel.decorations(frame.decorations,frame.frameMs,refreshAfterLoad);
        selectDialogueVariant();
        if (changed) {
            refreshAfterLoad=false;
            typing=false; fullBody=frame.displayBody.text;
            dialoguePanel.speaker(frame.speaker,frame.nameWindow);dialoguePanel.read(frame.alreadyRead);
            if(choices!=null){stage.removeView(choices);choices=null;}
            boolean dialogue=frame.wait==NativeGame.DIALOGUE;
            typing=dialogue&&initialVisible<frame.displayBody.glyphCount;
            revealed=dialogue?frame.displayBody.offsetForGlyphs(initialVisible):fullBody.length();
            dialoguePanel.message(frame.displayBody,revealed);
            if(frame.wait==NativeGame.CHOICE&&uiAssets!=null){
                choices=new GameChoicePanel(this,uiAssets,frame,preferences.getBoolean("changeSelectColor",false),
                    ()->{
                        if(runner==null||controls!=null&&controls.clicking())return false;
                        runner.choicePressed(frame);
                        return true;
                    },
                    number->{if(runner!=null)runner.input(frame,lastX,lastY,number);});
                choices.planeInvalidated(storyPlane::schedulePush);
                choices.sound((se,voice)->{if(runner!=null)runner.playClick(se,voice);});
                stage.addView(choices,stage.indexOfChild(dialoguePanel)+1,new FrameLayout.LayoutParams(width,height));
                if(stage.getWidth()>0&&stage.getHeight()>0)scaleLogicalView(choices,stage.getWidth(),stage.getHeight());
            }
            if(frame.wait==NativeGame.END){
                String end="Chapter ended · Open menu to load save or return to title";
                dialoguePanel.speaker("",false);dialoguePanel.message(end,end.length());
            }
        }
        updateVisibility(); updateStatus();
        if(frame.wait==NativeGame.PLACE&&gamePanel==null&&preparingPanel==null)showPlace(null);
    }
    @Override public void style(DialogueStyle style) {runOnUiThread(() -> {dialogueStyle=style;if(dialoguePanel!=null){dialoguePanel.style(style);reserveCommandSpace();storyPlane.schedulePush();}});}
    @Override public void choiceTime(GameRunner.Frame frame,int remaining){runOnUiThread(()->{
        if(choices!=null&&currentFrame==frame){choices.remaining(remaining);storyPlane.schedulePush();}
    });}
    private void selectDialogueVariant(){
        if(dialoguePanel==null||preferences==null)return;
        boolean line=currentFrame!=null&&currentFrame.wait==NativeGame.DIALOGUE;
        boolean draw=currentFrame!=null&&currentFrame.wait==NativeGame.DRAW;
        dialoguePanel.variant(currentFrame==null?0:currentFrame.windowType,currentFrame==null?0:currentFrame.fontType,
            preferences.getInt("messageWindowIndex",0),line&&currentFrame.layoutCommand==0x22220002,
            draw?currentFrame.showsDialogue():!line||currentFrame.windowVisible);
        reserveCommandSpace();
        storyPlane.schedulePush();
    }
    private void reserveCommandSpace(){
        if(dialoguePanel!=null&&controls!=null)dialoguePanel.contentRight(controls.contentRight(dialoguePanel.bodyX(),dialoguePanel.bodyY(),
            dialoguePanel.bodyY()+dialoguePanel.bodyHeight(),dialoguePanel.bodyX()+dialoguePanel.bodyWidth()));
    }
    @Override public void panels(GameUiAssets assets) {runOnUiThread(() -> {
        uiAssets=assets;if(isDestroyed()||stage==null)return;
        width=assets.sharedConfig.screenWidth;height=assets.sharedConfig.screenHeight;layoutStage();
        updatePointerIcon();
        storyPlane.schedulePush();
        dialoguePanel.cursorAssets(assets);
        if(controls!=null)stage.removeView(controls);
        controls=new GameStoryControls(this,assets,new GameStoryControls.Listener(){
            public void begin(GameStoryControlsModel.Action action){
                if(runner!=null){runner.menu(true);warmStoryPanel(action);}
            }
            public void sound(int se,int voice){if(runner!=null)runner.playClick(se,voice);}
            public void action(GameStoryControlsModel.Action action,boolean target){
                if(runner==null)return;
                switch(action){
                case MENU:showMenu();break;
                case CONFIG:runner.suspendScriptAudio();showSettings();break;
                case BACKLOG:showBacklog();break;
                case SAVE:runner.suspendScriptAudio();showSlots(true);break;
                case LOAD:runner.suspendScriptAudio();showSlots(false);break;
                case QUICK_SAVE:runner.quickSave(currentFrame);break;
                case QUICK_LOAD:runner.quickLoad(currentFrame);break;
                case AUTO:runner.auto(target);break;
                case SKIP:runner.skip(target);break;
                case FILM_SKIP:runner.skipFilm();break;
                case HIDE:hideUi();break;
                }
                if(panelsOpen==0&&preparingPanel==null)runner.menu(false);
            }
        });
        stage.addView(controls,new FrameLayout.LayoutParams(-1,-1));controls.canvas(width,height);
        if(quickNoticeView!=null)stage.removeView(quickNoticeView);
        quickNoticeView=new GameNotice(this,assets,cue->{if(runner!=null)runner.playClick(cue,-1);});
        stage.addView(quickNoticeView,new FrameLayout.LayoutParams(-1,-1));quickNoticeView.canvas(width,height);
        reserveCommandSpace();updateVisibility();updateStatus();
    });}
    @Override public void quickSlot(boolean available){runOnUiThread(()->{if(controls!=null)controls.quickSlot(available);});}
    @Override public void quickNotice(int number,int frameMs){runOnUiThread(()->{
        if(quickNoticeView!=null&&titlePanel==null&&panelsOpen==0&&!uiHidden)quickNoticeView.show(number,frameMs);
    });}
    @Override public void quickLoadNotice(int number,int frameMs,long generation,long submittedAt){
        runOnUiThread(()->storyPlane.afterTextureUpdate(generation,submittedAt,()->quickNotice(number,frameMs)));
    }
    @Override public void mode(boolean automatic,boolean skipping) {runOnUiThread(() -> {
        autoMode=automatic;skipMode=skipping;
        updateVisibility();updateStatus();
    });}
    @Override public void text(GameRunner.Frame frame,int visible,int total,int effect,int progress){runOnUiThread(()->{
        if(currentFrame!=frame)return;
        int count=Math.min(Math.max(0,visible),fullBody.codePointCount(0,fullBody.length()));
        revealed=frame.displayBody.offsetForGlyphs(count);dialoguePanel.reveal(revealed,effect,progress);
        boolean printing=count<total;
        if(typing!=printing){typing=printing;updateVisibility();}
        else storyPlane.schedulePush();
    });}
    @Override public void parameters(int[] display,int[] auxiliary){runOnUiThread(()->{
        pendingDisplay=display;pendingAuxiliary=auxiliary;ui.removeCallbacks(syncRuntimeDisplay);ui.post(syncRuntimeDisplay);
    });}
    private final Runnable syncRuntimeDisplay=new Runnable(){public void run(){
        if(uiAssets==null||pendingDisplay==null||isDestroyed())return;
        if(gamePanel!=null&&gamePanel.isAdjusting()){ui.postDelayed(this,40);return;}
        int[] display=pendingDisplay,aux=pendingAuxiliary;pendingDisplay=null;pendingAuxiliary=null;syncingRuntime=true;
        try{
            OriginalSettingsModel model=uiAssets.settingsModel(width,height);
            RuntimeSettingsAdapter.syncDisplay(preferences,model,display);
            if(aux!=null&&aux.length>=34){SharedPreferences.Editor edit=preferences.edit().putInt("systemVoiceSelection",aux[0]).putInt("messageWindowIndex",aux[1]);
                for(OriginalSettingsModel.CharacterControl group:model.characters){boolean enabled=(aux[2+(group.index&31)]&(1<<(group.index/32)))==0;
                    for(int id:group.ids)edit.putBoolean("character."+id+".enabled",enabled);}
                edit.apply();}
            settings=PlayerSettings.read(preferences,userPreferences);if(dialoguePanel!=null)dialoguePanel.settings(settings);
            if(aux!=null&&aux.length>=36){
                if(dialoguePanel!=null)dialoguePanel.cursorVariant(aux[34]);
                if(mouseVariant!=aux[35]){mouseVariant=aux[35];updatePointerIcon();}
            }
            if(choices!=null)choices.changeColor(preferences.getBoolean("changeSelectColor",false));
            selectDialogueVariant();immersive();updateStatus();
        }finally{syncingRuntime=false;}
    }};
    @Override public void scriptMouse(int variant){runOnUiThread(()->{
        if(scriptMouse!=variant){scriptMouse=variant;updatePointerIcon();}
    });}
    private void updatePointerIcon(){
        if(!isDestroyed()&&stage!=null&&uiAssets!=null&&mouseVariant!=Integer.MIN_VALUE)
            stage.setPointerIcon(uiAssets.pointer(mouseVariant,scriptMouse));
    }
    private void advance() {
        if(runner==null || launchingGame || panelsOpen>0)return;
        if(nativePanelActive()){runner.nativePanelInput(currentFrame,lastX,lastY,true,false);return;}
        if(autoMode)runner.auto(false);
        if(skipMode)runner.skip(false);
        autoMode=false;skipMode=false;updateStatus();
        if(uiHidden){uiHidden=false;updateVisibility();return;}
        if(currentFrame!=null && currentFrame.wait==NativeGame.END){showMenu();return;}
        runner.input(currentFrame,lastX,lastY,0);
    }
    private void hideUi() {if(runner==null)return;runner.auto(false);runner.skip(false);if(quickNoticeView!=null)quickNoticeView.clear();uiHidden=true;updateVisibility();}
    private boolean panelActionReady(){
        return runner!=null&&preparingPanel==null&&!returningToTitle&&gamePanel!=null&&
            gamePanel.activeFrameReady()&&!gamePanel.openingRunning()&&!gamePanel.controlsBusy();
    }
    private void updatePanelActionEnabled(){
        if(panelActionButton==null)return;
        boolean ready=panelActionReady();
        if(panelActionButton.getVisibility()==View.VISIBLE&&panelActionButton.isEnabled()!=ready)
            panelActionButton.setEnabled(ready);
    }
    private void updatePanelAction(){
        String label=movieErrorPanel==null&&gamePanel instanceof GameSettingsPanel?"Text & Font":null;
        if(label!=null&&!label.contentEquals(panelActionButton.getText()))panelActionButton.setText(label);
        panelActionButton.setVisibility(label==null?View.GONE:View.VISIBLE);
        updatePanelActionEnabled();
    }
    private void updateVisibility() {
        if(dialoguePanel==null)return;
        updatePanelAction();
        if(gamePanel==null&&isModalPanel(preparingPanel))return;
        boolean storySession=!launchingGame&&titlePanel==null&&moviePlayback==null&&movieErrorPanel==null&&!storyMovieExitActive;
        boolean storyVisible=panelsOpen==0||gamePanel!=null&&gamePanel.awaitingCapturedBackdrop();
        boolean inGame=storySession&&storyVisible;
        if(!storySession||currentFrame!=null&&(currentFrame.wait==NativeGame.MODAL||currentFrame.wait==NativeGame.NATIVE_PANEL))storyPlane.frame(null);
        boolean hasDialogue=currentFrame!=null&&(currentFrame.showsDialogue()||
            currentFrame.wait==NativeGame.END);
        boolean hasChoices=currentFrame!=null&&currentFrame.wait==NativeGame.CHOICE;
        dialoguePanel.setVisibility(!storySession||!hasDialogue?View.GONE:inGame&&!uiHidden?View.VISIBLE:View.INVISIBLE);
        dialoguePanel.waiting(inGame&&!uiHidden&&hasDialogue&&!typing&&!skipMode&&currentFrame.wait==NativeGame.DIALOGUE,
            autoMode,currentFrame==null?0:currentFrame.layoutCommand,currentFrame==null?20:currentFrame.frameMs);
        if(controls!=null){if(inGame&&!uiHidden)controls.frame(currentFrame,typing,choices,sceneReplay);else controls.setVisibility(View.GONE);}
        if(choices!=null)choices.setVisibility(!storySession||!hasChoices?View.GONE:inGame&&!uiHidden?View.VISIBLE:View.INVISIBLE);
        storyPlane.schedulePush();
        syncIndependentStatus();
    }
    private int visiblePrintMode(){
        if(movieErrorPanel!=null)return GameCodecErrorPanel.MODE;
        if(moviePlayback!=null)return 27;
        if(logoPanel!=null)return 2;
        if(startupOpening!=null)return 3;
        if(gamePanel!=null)return gamePanel.printMode;
        if(titlePanel!=null)return 4;
        if(currentFrame==null)return 1;
        switch(currentFrame.wait){
            case NativeGame.DIALOGUE:return 10;
            case NativeGame.CHOICE:return 9;
            case NativeGame.DRAW:return 11;
            case NativeGame.OVERLAP:return 14;
            case NativeGame.PLACE:return currentFrame.placeMode;
            case NativeGame.NATIVE_PANEL:return currentFrame.nativePanelMode;
            default:return 1;
        }
    }
    private void syncIndependentStatus(){
        if(runner==null||uiAssets==null||!uiAssets.statusOverlays||stage==null)return;
        if(currentFrame!=null&&currentFrame.wait==NativeGame.NATIVE_PANEL&&gamePanel==null){
            independentMode=0;runner.statusMode(0);
            if(independentStatus!=null)independentStatus.setVisibility(View.GONE);
            return;
        }
        int mode=visiblePrintMode();
        if(mode!=independentMode){
            independentMode=mode;
            if(independentStatus!=null)independentStatus.setVisibility(View.GONE);
            runner.statusMode(mode);
        }
        placeIndependentStatus();
    }
    private void placeIndependentStatus(){
        if(independentStatus==null)return;
        View anchor=moviePlayback!=null?moviePlayback:logoPanel!=null?logoPanel:
            gamePanel!=null?gamePanel:titlePanel!=null?titlePanel:sceneEntrance;
        int index=anchor!=null?stage.indexOfChild(anchor)+1:controls==null?stage.getChildCount():stage.indexOfChild(controls);
        int old=stage.indexOfChild(independentStatus);
        if(old>=0&&old<index)index--;
        if(old!=index){
            if(old>=0)stage.removeView(independentStatus);
            stage.addView(independentStatus,Math.max(0,index),new FrameLayout.LayoutParams(-1,-1));
        }
        if(gamePanel!=null)gamePanel.statusForCapture(independentStatus);
    }
    @Override public void statusOverlays(int mode,GameRunner.Modal[] overlays){
        runOnUiThread(()->{
            if(stage==null||uiAssets==null||mode!=independentMode||isDestroyed()||isFinishing())return;
            if(independentStatus==null){
                if(overlays.length==0)return;
                independentStatus=new FrameLayout(this);
                independentStatus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            int used=0;
            for(GameRunner.Modal overlay:overlays){
                int index=overlay.mode==GameRunner.Modal.PRINT_PARTY?1:0;
                used|=1<<index;
                GameStatusPartyPanel part=independentParts[index];
                if(part==null){
                    part=new GameStatusPartyPanel(this,uiAssets,overlay,width,height);
                    independentParts[index]=part;
                    independentStatus.addView(part,new FrameLayout.LayoutParams(-1,-1));
                }else {part.canvas(width,height);part.update(overlay);}
                independentStatus.bringChildToFront(part);
            }
            for(int i=0;i<independentParts.length;i++)if((used&(1<<i))==0&&independentParts[i]!=null){
                independentStatus.removeView(independentParts[i]);
                independentParts[i]=null;
            }
            independentStatus.setVisibility(independentStatus.getChildCount()==0?View.GONE:View.VISIBLE);
            placeIndependentStatus();
            statusActive(foreground);
        });
    }
    private void statusActive(boolean active){
        for(GameStatusPartyPanel panel:independentParts)if(panel!=null)panel.uiActive(active);
    }
    private void updateStatus() {
        if(controls!=null)controls.modes(autoMode,skipMode);
    }
    private void clearStartupViews(){
        if(logoPanel!=null){stage.removeView(logoPanel);logoPanel=null;}
        if(startupOpening!=null){stage.removeView(startupOpening);startupOpening=null;}
        storyMovieExitActive=false;
        stage.setForeground(null);
    }
    private void removeTitleForStartup(){
        if(titlePanel==null)return;
        stage.removeView(titlePanel);
        titlePanel=null;
        titleModel=null;
        launchingGame=true;
    }
    private void clearStartupForStory(){
        if(!launchingGame)return;
        launchingGame=false;
        clearStartupViews();
        titlePrevious=null;
        MoviePlayback previous=moviePlayback;moviePlayback=null;
        if(previous!=null){previous.dispose();stage.removeView(previous);}
    }
    @Override public void logo(List<LogoPanel.Slide> slides,int w,int h,int frameMs){
        runOnUiThread(()->{
            GameRunner owner=runner;
            if(isDestroyed()||isFinishing()||stage==null||owner==null)return;
            if(w>0&&h>0){width=w;height=h;}layoutStage();
            if(slides.isEmpty())return;
            clearStartupViews();
            logoPanel=new LogoPanel(this,slides,frameMs,()->{
                if(isDestroyed()||isFinishing()||runner!=owner||logoPanel==null)return;
                titlePrevious=logoPanel.exitScreen();
                owner.finishLogo();
            });
            stage.addView(logoPanel,new FrameLayout.LayoutParams(-1,-1));
            removeTitleForStartup();
            updateVisibility();
        });
    }
    @Override public void opening(GameUiAssets.Config config,int frameMs,GameRunner.Capture initial,GameRunner.Capture previous){
        runOnUiThread(()->{
            GameRunner owner=runner;
            if(isDestroyed()||isFinishing()||stage==null||owner==null)return;
            clearStartupViews();
            if(previous!=null)titlePrevious=previous.bitmap();
            startupOpening=new OpeningScreen(owner,config,frameMs,initial);
            stage.addView(startupOpening,new FrameLayout.LayoutParams(-1,-1));
            removeTitleForStartup();
            updateVisibility();
        });
    }
    private final class OpeningScreen extends View {
        private final GameRunner owner;
        private final UiOpening transition;
        private boolean shown;
        OpeningScreen(GameRunner owner,GameUiAssets.Config config,int frameMs,GameRunner.Capture initial){
            super(PlayerActivity.this);
            this.owner=owner;
            transition=new UiOpening(config,frameMs,false);
            transition.previous(titlePrevious);
            transition.target(initial==null?null:initial.bitmap());
            setSoundEffectsEnabled(false);
            setContentDescription("Opening movie, tap to skip");
            setOnClickListener(v->owner.finishOpening());
        }
        @Override protected void onDraw(Canvas canvas){
            transition.begin();
            transition.drawTarget(canvas,this);
            transition.overlay(canvas,this,width,height);
            if(transition.fading())postInvalidateOnAnimation();
            else if(!shown){shown=true;owner.openingShown();}
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(transition.touch(event)){invalidate();return true;}
            return super.onTouchEvent(event);
        }
        void skip(){
            if(transition.fading()){transition.skip();invalidate();}
            else owner.finishOpening();
        }
        @Override protected void onDetachedFromWindow(){transition.close();super.onDetachedFromWindow();}
    }
    @Override public void titleRestart(TitleMenu model,GameRunner.Capture background){
        runOnUiThread(()->{
            GameRunner owner=runner;
            TitlePanel panel=titlePanel;
            if(owner==null)return;
            if(!foreground||isDestroyed()||isFinishing()||model!=titleModel||panel==null||
                panelsOpen!=0||preparingPanel!=null||gamePanel!=null||galleryPage!=null||returningToTitle||
                !panel.leaveForRestart(background,picture->{
                    if(owner!=runner||panel!=titlePanel||isDestroyed()||isFinishing())return;
                    titlePrevious=picture;
                    owner.finishTitleRestart(model,true);
                }))owner.finishTitleRestart(model,false);
        });
    }
    @Override public void title(TitleMenu model,int w,int h,GameRunner.Capture initial,GameRunner.Capture previous) {
        runOnUiThread(() -> {
            if(isDestroyed() || isFinishing() || stage==null)return;
            if(titleModel!=model||returningToTitle)modeUnderPanel=null;
            if(returningToTitle){returningToTitle=false;closeOriginalPanel(false);}
            if(previous!=null){closeOriginalPanel(false);titlePrevious=previous.bitmap();}
            typing=false;currentFrame=null;fullBody="";uiHidden=false;refreshAfterLoad=false;
            if(titlePanel!=null)stage.removeView(titlePanel);
            titleModel=model;if(w>0&&h>0){width=w;height=h;}layoutStage();
            dialoguePanel.directDraw(false);
            dialoguePanel.setVisibility(View.GONE);if(controls!=null)controls.setVisibility(View.GONE);
            if(choices!=null){stage.removeView(choices);choices=null;}
            storyPlane.clear();
            launchingGame=false;
            clearStartupViews();
            showTitlePanel(model,initial);
        });
    }
    private void showTitlePanel(TitleMenu model,GameRunner.Capture initial){
        titlePanel=new TitlePanel(this,game.getName(),model,width,height,runner,initial==null?null:initial.bitmap(),this::titleAction,
            item->warmTitlePanel(item.action),
            (se,voice)->{if(runner!=null)runner.playClick(se,voice);});
        titlePanel.previous(titlePrevious);
        titlePrevious=null;
        stage.addView(titlePanel,new FrameLayout.LayoutParams(-1,-1));
        syncIndependentStatus();
        if(openGalleryAfterTitle){
            openGalleryAfterTitle=false;
            if(gamePanel!=null)gamePanel.bringToFront();
            openGallery(GalleryCatalog.Screen.OMAKE,0,0,-1);
        }
    }
    private void warmTitlePanel(TitleMenu.Action action){
        if(action==TitleMenu.Action.SETTINGS)warmStoryPanel(GameStoryControlsModel.Action.CONFIG);
        else if(action==TitleMenu.Action.LOAD)warmStoryPanel(GameStoryControlsModel.Action.LOAD);
    }
    private int openingSettingsPage(){
        return GameSettingsPanel.openingPage(uiAssets.config,
            GameSettingsPanel.pageCount(uiAssets.settingsModel(width,height)),settingsPage);
    }
    private boolean inSceneReplay(){
        return sceneReplay||runner!=null&&runner.isGallerySceneReplay();
    }
    private void warmStoryPanel(GameStoryControlsModel.Action action){
        if(uiAssets==null||uiAssets.runtime==null)return;
        UiPreviewPool.Request request;
        switch(action){
        case CONFIG:request=UiPreviewPool.Request.read(uiAssets.config,12,openingSettingsPage()+1);break;
        case SAVE:case LOAD:
            boolean saving=action==GameStoryControlsModel.Action.SAVE;
            GameUiAssets.Config config=saving?uiAssets.save:uiAssets.load;
            SaveSlots.Layout layout=new SaveSlots.Layout(config,uiAssets.game,uiAssets.dataFile,saving);
            request=UiPreviewPool.Request.read(config,saving?6:5,layout.openingPcPage(runner.lastSelectedSlot())+1);
            break;
        default:return;
        }
        UiPreviewPool pool=request==null?null:uiAssets.runtime.previews();
        panelWarmup=pool==null?null:pool.warm(request);
    }
    private UiPreviewPool.Warmup takePanelWarmup(){
        UiPreviewPool.Warmup warmup=panelWarmup;panelWarmup=null;return warmup;
    }
    private void titleAction(TitleMenu.Item item) {
        if(runner==null || titleModel==null)return;
        TitleMenu.Action action=item.action;
        switch(action) {
        case START:case CONTINUE:case LAST_LOAD:case SCENARIO:
            TitlePanel source=titlePanel;
            GameRunner owner=runner;
            owner.enter(action,titleModel.scenarioVariable,item.scenario,()->runOnUiThread(()->{
                if(source!=null&&source==titlePanel&&owner==runner&&!isDestroyed())source.entryFailed();
            }));
            break;
        case LOAD:showSlots(false);break;
        case SETTINGS:showSettings();break;
        case EXTRAS:openGallery(GalleryCatalog.Screen.OMAKE,0,0,-1);break;
        case EXIT:finish();break;
        case WEBSITE:
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(titleModel.website)));}
            catch(android.content.ActivityNotFoundException e){notice("Web browser not found");}break;
        case MODE:openMenuMode(item.mode,GameRunner.Modal.TITLE);break;
        case MINIGAME:openMiniGame(item.mode,false);break;
        default:break;
        }
    }
    private void openGallery(GalleryCatalog.Screen screen,int group,int page,int index) {
        if (runner==null || titlePanel==null) return;
        if (screen==GalleryCatalog.Screen.VIEW && galleryPage!=null && galleryPage.screen==GalleryCatalog.Screen.CG)
            galleryCgPage=galleryPage.page;
        runner.requestGallery(screen,group,page,index,++galleryRequest);
    }
    private final GameGalleryPanel.Actions galleryActions=new GameGalleryPanel.Actions() {
        public void open(GalleryCatalog.Screen screen,int group,int page,int index) { openGallery(screen,group,page,index); }
        public void mode(int mode) {openMenuMode(mode,GameRunner.Modal.OMAKE);}
        public void miniGame(int index) {openMiniGame(index,true);}
        public void back() { galleryBack(); }
        public void replay(int group,int index) {
            if (runner!=null)runner.replayGallery(group,index);
        }
        public void movie(int index) { if(runner!=null)runner.playGalleryMovie(index); }
        public void play(int index) { if (runner!=null) runner.playGallerySong(index); }
        public void moveSong(int direction) { if (runner!=null) runner.moveGallerySong(direction); }
        public void pause() { if (runner!=null) runner.pauseGallerySong(); }
        public void stop() { if (runner!=null) runner.stopGallerySong(); }
        public void volume(int volume) { if (runner!=null) runner.gallerySongVolume(volume); }
        public void cgFrame(GalleryCatalog.Page page,int frame,java.util.function.Consumer<Bitmap> done){
            GameRunner owner=runner;
            if(owner==null){done.accept(null);return;}
            owner.requestCgFrame(page,frame,bitmap->runOnUiThread(()->done.accept(owner==runner?bitmap:null)));
        }
    };
    private void openMiniGame(int index,boolean omake){
        GameRunner owner=runner;
        if(owner==null||uiAssets==null)return;
        GameUiPanel previous=gamePanel;
        owner.requestMiniGame(index,game->runOnUiThread(()->{
            if(game==null||runner!=owner||isDestroyed()||isFinishing()||gamePanel!=previous)return;
            Runnable back=omake?()->openGallery(GalleryCatalog.Screen.OMAKE,0,0,-1):()->leaveOriginalPanel(game.config);
            GameMiniGamePanel panel=new GameMiniGamePanel(this,uiAssets,game,width,height,
                sound->owner.playClick(sound,-1),()->owner.completeMiniGame(index),back);
            showOriginalPanel(panel,back);
        }));
    }
    @Override public void gallery(GalleryCatalog.Page page,long request) {
        runOnUiThread(() -> {
            if (request!=galleryRequest || isDestroyed() || isFinishing() || titlePanel==null || uiAssets==null) return;
            galleryPage=page;
            if (gamePanel instanceof GameGalleryPanel && ((GameGalleryPanel)gamePanel).screen()==page.screen)
                ((GameGalleryPanel)gamePanel).update(page);
            else {
                GameGalleryPanel panel=new GameGalleryPanel(this,uiAssets,page,this::systemClick,galleryActions,width,height);
                Bitmap previous=optionalGalleryPrevious;optionalGalleryPrevious=null;
                if(previous==null)showOriginalPanel(panel,this::galleryBack);
                else {runner.menu(true);panel.previous(previous);mountOriginalPanel(panel,this::galleryBack);}
            }
        });
    }
    @Override public void spectrum(int[] bands){runOnUiThread(()->{
        if(gamePanel instanceof GameGalleryPanel)((GameGalleryPanel)gamePanel).spectrum(bands);
    });}
    private void galleryBack() {
        if(galleryPage==null||gamePanel==null)return;
        GalleryCatalog.Page page=galleryPage;
        switch (page.screen) {
        case VIEW:
            runner.playClick(page.config.number("backSound",2)-1,-1);
            openGallery(GalleryCatalog.Screen.CG,page.group,galleryCgPage,-1); break;
        case CG:
            openGallery(page.cgGroups?GalleryCatalog.Screen.CG_GROUP:GalleryCatalog.Screen.OMAKE,0,0,-1); break;
        case SCENE:
            openGallery(page.sceneGroups?GalleryCatalog.Screen.SCENE_GROUP:GalleryCatalog.Screen.OMAKE,0,0,-1); break;
        case OMAKE:
            leaveOriginalPanel(page.config); break;
        default:
            openGallery(GalleryCatalog.Screen.OMAKE,0,0,-1); break;
        }
    }
    @Override public void galleryReplayStarted() {
        runOnUiThread(() -> {
            if (isDestroyed() || isFinishing()) return;
            sceneReplay=true; galleryPage=null; ++galleryRequest;
            closeOriginalPanel(false);
        });
    }
    private GameCodecErrorPanel movieErrorPanel;
    @Override public void movieError(GameRunner.MovieError error){runOnUiThread(()->{
        if(isFinishing()||isDestroyed()||runner!=error.owner||stage==null)return;
        width=uiAssets.sharedConfig.screenWidth;height=uiAssets.sharedConfig.screenHeight;layoutStage();
        clearCtrlSkip();
        clearStartupViews();
        if(gamePanel!=null)gamePanel.uiActive(false);
        for(int i=0;i<stage.getChildCount();i++)
            stage.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        GameCodecErrorPanel panel=new GameCodecErrorPanel(this,uiAssets,error,action->{
            if(runner!=error.owner||movieErrorPanel==null||movieErrorPanel.error!=error)return;
            if(action==2){finish();return;}
            GameCodecErrorPanel current=movieErrorPanel;
            current.leave(error.data.config,false,(picture,filled)->{
                if(runner!=error.owner||movieErrorPanel!=current||isFinishing()||isDestroyed())return;
                current.discardBackground();stage.removeView(current);movieErrorPanel=null;
                if(!error.startup&&titlePanel==null&&gamePanel==null)storyMovieExitActive=true;
                for(int i=0;i<stage.getChildCount();i++)
                    stage.getChildAt(i).setImportantForAccessibility(gamePanel==null||stage.getChildAt(i)==gamePanel
                        ?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                if(gamePanel!=null)gamePanel.uiActive(foreground);
                error.owner.resolveMovieError(error,action==1);
                updateVisibility();
            });
        });
        movieErrorPanel=panel;
        panel.setFocusableInTouchMode(true);
        panel.sound((se,voice)->error.owner.playClick(se,voice));
        panel.uiActive(foreground);
        stage.addView(panel,new FrameLayout.LayoutParams(-1,-1));
        panel.requestFocus();
        updateVisibility();immersive();
    });}
    @Override public void movie(File file,float gain,boolean canSkip,boolean startup,Integer exitColor,int tracks){runOnUiThread(()->{
        if(isFinishing()||isDestroyed()||stage==null||runner==null)return;
        GameRunner owner=runner;
        if(!startup)clearStartupForStory();
        if(gamePanel!=null)gamePanel.uiActive(false);
        for(int i=0;i<stage.getChildCount();i++)
            stage.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        moviePlayback=new MoviePlayback(this,file,gain,canSkip,tracks,(source,outcome,failure)->{
            if(owner!=runner||source!=moviePlayback)return;
            moviePlayback=null;
            if(exitColor!=null){
                storyMovieExitActive=!startup;
                stage.setForeground(new ColorDrawable(exitColor));
            }else if(storyMovieExitActive){
                storyMovieExitActive=false;
                stage.setForeground(null);
            }
            stage.removeView(source);
            for(int i=0;i<stage.getChildCount();i++)
                stage.getChildAt(i).setImportantForAccessibility(gamePanel==null||stage.getChildAt(i)==gamePanel
                    ?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if(gamePanel!=null)gamePanel.uiActive(outcome!=MoviePlayback.Outcome.FAILED&&foreground);
            owner.finishMovie(outcome,failure);
            updateVisibility();
        });
        if(!foreground)moviePlayback.pause();
        stage.addView(moviePlayback,new FrameLayout.LayoutParams(-1,-1));
        if(startup){
            clearStartupViews();
            removeTitleForStartup();
        }
        updateVisibility();immersive();
    });}
    @Override public void galleryReplayFinished(GameRunner owner,TitleMenu title,GalleryCatalog.Page page,int w,int h) {
        runOnUiThread(() -> {
            if (isDestroyed() || isFinishing() || runner!=owner) return;
            sceneReplay=false;currentFrame=null;typing=false;fullBody="";uiHidden=false;
            modeUnderPanel=null;visibleModal=null;refreshAfterLoad=false;
            closeOriginalPanel(false);clearStartupViews();storyPlane.clear();
            if(titlePanel!=null)stage.removeView(titlePanel);
            titleModel=title;width=w;height=h;layoutStage();
            showTitlePanel(title,null);
            // Keep the title model for gallery navigation; its background starts only on an actual return to title.
            titlePanel.setVisibility(View.INVISIBLE);
            galleryPage=page;++galleryRequest;
            GameGalleryPanel panel=new GameGalleryPanel(this,uiAssets,page,this::systemClick,galleryActions,width,height);
            Bitmap previous=optionalGalleryPrevious;optionalGalleryPrevious=null;
            if(previous!=null)panel.previous(previous);
            mountOriginalPanel(panel,this::galleryBack);
            owner.titleShown();
        });
    }
    private void showMenu() {
        if(runner==null){finish();return;}
        if(moviePlayback!=null||movieErrorPanel!=null||storyMovieExitActive)return;
        if(nativePanelActive()){runner.nativePanelMenu(currentFrame,13);return;}
        boolean modalOptions=visibleModal!=null&&(visibleModal.advancedSelection()||
            visibleModal.mode==GameRunner.Modal.SELECT_CHART||visibleModal.mode==GameRunner.Modal.SELECT_PLACE2);
        if(currentFrame!=null&&currentFrame.wait==NativeGame.MODAL&&!modalOptions)return;
        if(!modalOptions&&choices!=null){
            if(choices.confirming()||controls!=null&&controls.clicking())return;
            if(!choices.menuAllowed()){if(choices.backlogAllowed())showBacklog();return;}
        }else if(currentFrame!=null&&!currentFrame.optionsAllowed)return;
        if(controls!=null)controls.cancelClick();
        if(titlePanel==null)storyPlane.flushForCapture();
        if(uiAssets!=null&&uiAssets.system.has("Menu1Button1FileName")){showOriginalMenu();return;}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Game Menu")
            .setItems(new String[]{"Resume","Save","Load","Settings","Backlog","Title Menu",fromTyn?"Return to TYN":"Exit Game"},(d,item) -> {
                systemClick();
                switch(item){case 1:if(!inSceneReplay())showSlots(true);break;case 2:if(!inSceneReplay())showSlots(false);break;case 3:showSettings();break;
                case 4:showBacklog();break;case 5:if(inSceneReplay())runner.endGalleryReplay();else runner.returnToTitle();break;case 6:finish();break;default:break;}
            }).setNegativeButton("Close",dialogClick(null)).create();
        showPanel(dialog);
        runner.suspendScriptAudio();
    }
    private void showPanel(AlertDialog dialog) {
        trackModalDialog(dialog, null);
        dialog.show();
        for(int which:new int[]{DialogInterface.BUTTON_POSITIVE,DialogInterface.BUTTON_NEGATIVE,DialogInterface.BUTTON_NEUTRAL})
            if(dialog.getButton(which)!=null)dialog.getButton(which).setSoundEffectsEnabled(false);
        if(dialog.getListView()!=null)dialog.getListView().setSoundEffectsEnabled(false);
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(dp(720),Math.max(dp(280),root.getWidth()-dp(40))),-2);
    }
    private void trackModalDialog(AlertDialog dialog, Runnable onExtraDismiss) {
        panelsOpen++;openedPanels.add(dialog);if(runner!=null)runner.menu(true);updateVisibility();
        dialog.setOnDismissListener(d -> {
            if(onExtraDismiss!=null)onExtraDismiss.run();
            openedPanels.remove(dialog);panelsOpen=Math.max(0,panelsOpen-1);
            if(isOverlayMode(gamePanel)&&modeUnderPanel!=null&&openedPanels.isEmpty()){
                if(resumeModeAfterPanel(null))return;
                lockModalOverlay(gamePanel,false);
            }
            if(runner!=null&&panelsOpen==0&&!isFinishing())runner.menu(false);
            updateVisibility();updateStatus();immersive();
        });
    }
    private void showOriginalPanel(GameUiPanel panel,Runnable back) {
        showOriginalPanel(panel,back,null);
    }
    private void showOriginalPanel(GameUiPanel panel,Runnable back,GameUiAssets.Config sceneEffect) {
        if(preparingPanel!=null){panel.discardBackground();return;}
        boolean needsPrevious=panel.wantsPrevious();
        GameRunner owner=runner;
        if(owner!=null)owner.menu(true);
        if(owner==null||!needsPrevious&&sceneEffect==null){
            mountOriginalPanel(panel,back);
            return;
        }
        // Keep the outgoing view visible until both transition and menu images are ready.
        preparingPanel=panel;
        if(sceneEffect!=null&&(captureStoryUi(sceneEffect)||needsPrevious)&&
            (gamePanel instanceof GameAdvancedSelectionPanel||gamePanel instanceof GamePlacePanel)){
            boolean withStoryUi=captureStoryUi(sceneEffect);
            gamePanel.capture(before->{
                if(preparingPanel!=panel)return;
                if(before==null){captureForPanel(panel,back,sceneEffect,needsPrevious,owner);return;}
                if(withStoryUi)filterCapturedPanel(panel,back,sceneEffect,needsPrevious,owner,before);
                else captureNativeBackground(panel,back,sceneEffect,owner,before);
            });
            return;
        }
        if(needsPrevious&&gamePanel!=null&&sceneEffect==null){
            gamePanel.capture(before->{
                if(!acceptPreparedPanel(panel,owner))return;
                panel.previous(before);
                mountOriginalPanel(panel,back);
            });
            return;
        }
        if(needsPrevious&&sceneEffect==null&&titlePanel!=null&&copyTitleForPanel(panel,back,owner))return;
        captureForPanel(panel,back,sceneEffect,needsPrevious,owner);
    }
    private boolean copyTitleForPanel(GameUiPanel panel,Runnable back,GameRunner owner){
        TitlePanel title=titlePanel;
        int w=title.getWidth(),h=title.getHeight();
        Surface source=storySurface;
        if(w<=0||h<=0||source==null||!source.isValid())return false;
        float scale=UiOpening.snapshotScale(w,h);
        Bitmap before=Bitmap.createBitmap(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)),Bitmap.Config.ARGB_8888);
        before.setHasAlpha(false);
        try{
            // Read the presented title frame without waiting behind its next script step.
            PixelCopy.request(source,before,result->{
                if(result!=PixelCopy.SUCCESS){
                    before.recycle();
                    if(preparingPanel==panel)captureForPanel(panel,back,null,true,owner);
                    return;
                }
                if(!acceptPreparedPanel(panel,owner)){before.recycle();return;}
                UiOpening.drawOnto(title,before,c->drawTitleCapture(title,c));
                panel.previous(before);
                mountOriginalPanel(panel,back);
            },ui);
            return true;
        }catch(IllegalArgumentException e){
            before.recycle();
            return false;
        }
    }
    private boolean captureStoryUi(GameUiAssets.Config config){
        if(config==null||titlePanel!=null||currentFrame==null||uiAssets==null)return false;
        if(config==uiAssets.backlog&&config.number("backScriptFlag",0)==0)return false;
        if(config.number("backScriptFlag",0)!=0&&config.number("getWithMessageFlag",1)==0)return false;
        String eraseFlag=currentFrame.wait==NativeGame.CHOICE?"selectMiniCGNoMessageFlag":"messageMiniCGNoMessageFlag";
        return uiAssets.game.number(eraseFlag,0)==0;
    }
    private void captureForPanel(GameUiPanel panel,Runnable back,GameUiAssets.Config sceneEffect,boolean needsPrevious,GameRunner owner){
        boolean withStoryUi=captureStoryUi(sceneEffect);
        if(titlePanel==null)storyPlane.flushForCapture();
        owner.capture(sceneEffect,withStoryUi,needsPrevious,(previous,scene)->runOnUiThread(()->{
            if(preparingPanel!=panel)return;
            Bitmap before=null;
            try{
                TitlePanel title=titlePanel;
                if(previous!=null){
                    if(title!=null)before=UiOpening.snapshot(title,previous.bitmap(),canvas->drawTitleCapture(title,canvas));
                    else before=snapshotStory(previous,true);
                }
            }catch(RuntimeException|OutOfMemoryError e){
                Log.w("SystemNNN","Unable to capture the full menu screen",e);
            }
            finishCapturedPanel(panel,back,sceneEffect,owner,before,scene);
        }));
    }
    private void filterCapturedPanel(GameUiPanel panel,Runnable back,GameUiAssets.Config effect,boolean needsPrevious,
                                     GameRunner owner,Bitmap before){
        owner.filterPanel(before,effect,filtered->runOnUiThread(()->{
            if(preparingPanel!=panel)return;
            Bitmap previous=needsPrevious?before:null;
            if(filtered!=null)finishCapturedPanel(panel,back,effect,owner,previous,filtered);
            else captureNativeBackground(panel,back,effect,owner,previous);
        }));
    }
    private void captureNativeBackground(GameUiPanel panel,Runnable back,GameUiAssets.Config effect,GameRunner owner,Bitmap before){
        owner.capture(effect,false,false,(unused,scene)->runOnUiThread(()->finishCapturedPanel(panel,back,effect,owner,before,scene)));
    }
    private void finishCapturedPanel(GameUiPanel panel,Runnable back,GameUiAssets.Config effect,GameRunner owner,
                                     Bitmap before,GameRunner.Capture scene){
        if(!acceptPreparedPanel(panel,owner))return;
        if(before!=null)panel.previous(before);
        if(effect!=null)installCapturedBackground(panel,effect,scene);
        mountOriginalPanel(panel,back);
    }
    private void installCapturedBackground(GameUiPanel panel,GameUiAssets.Config config,GameRunner.Capture scene){
        int mode=panel instanceof GameBacklogPanel?7:13;
        if(scene==null){panel.uiBackground(config,mode,1);return;}
        panel.capturedBackground(scene.bitmap(),()->{if(gamePanel==panel)updateVisibility();});
        panel.uiBackground(config,mode,1,scene.pixels,scene.width,scene.height);
    }
    private void drawTitleCapture(TitlePanel title,Canvas canvas){
        title.draw(canvas);
        if(independentMode==4&&independentStatus!=null&&independentStatus.getVisibility()==View.VISIBLE)
            independentStatus.draw(canvas);
    }
    private Bitmap snapshotStory(GameRunner.Capture picture,boolean withFloatingUi){
        return UiOpening.snapshot(stage,picture.bitmap(),canvas->{
            if(!withFloatingUi||uiHidden)return;
            for(int i=0;i<stage.getChildCount();i++){
                View child=stage.getChildAt(i);
                if(child==controls||child==quickNoticeView||child==independentStatus)drawStageChild(canvas,child);
            }
        });
    }
    private static void drawStageChild(Canvas canvas,View child){
        if(child==null||child.getVisibility()!=View.VISIBLE||child.getAlpha()<=0)return;
        int saved=canvas.save();
        canvas.translate(child.getLeft(),child.getTop());
        canvas.concat(child.getMatrix());
        canvas.clipRect(0,0,child.getWidth(),child.getHeight());
        if(child.getAlpha()<1)canvas.saveLayerAlpha(0,0,child.getWidth(),child.getHeight(),Math.round(child.getAlpha()*255));
        child.draw(canvas);
        canvas.restoreToCount(saved);
    }

    private final class StoryPlane {
        private GameRunner owner;
        private GameRunner.Frame frame;
        private MessagePlaneToken token;
        private Bitmap bitmap;
        private Canvas canvas;
        private Bitmap floatingBitmap;
        private Canvas floatingCanvas;
        private long textureTimestamp,noticeTimestamp;
        private Runnable pendingLoadNotice;
        private boolean dirty,scheduled;
        private int rasterFailures;
        private boolean rasterFailed;
        private final Runnable retryRaster=this::schedulePush;
        private final Runnable animationFlush=()->{
            scheduled=false;
            if(!isDestroyed()&&!isFinishing())pushNow();
        };

        void frame(GameRunner.Frame next){
            if(frame!=next){rasterFailures=0;rasterFailed=false;ui.removeCallbacks(retryRaster);}
            frame=next;
        }
        void onTokenAvailable(){
            if(runner==null){release();return;}
            if(owner!=runner){release();owner=runner;}
            MessagePlaneToken next=runner.takePlaneToken();
            if(next!=null){
                if(token!=null&&token!=next)token.close();
                token=next;
                rasterFailures=0;rasterFailed=false;ui.removeCallbacks(retryRaster);
                pendingLoadNotice=null;
                schedulePush();
            }
        }
        void textureReset(){textureTimestamp=0;}
        void textureUpdated(long timestamp){
            textureTimestamp=timestamp;
            if(pendingLoadNotice!=null&&timestamp>=noticeTimestamp){
                Runnable notice=pendingLoadNotice;
                pendingLoadNotice=null;
                notice.run();
            }
        }
        void afterTextureUpdate(long generation,long timestamp,Runnable notice){
            onTokenAvailable();
            if(token==null||!token.acceptsFrame(generation)||isDestroyed()||isFinishing())return;
            if(textureTimestamp>=timestamp)notice.run();
            else{noticeTimestamp=timestamp;pendingLoadNotice=notice;}
        }
        void schedulePush(){
            dirty=true;
            if(scheduled||stage==null||isDestroyed()||isFinishing())return;
            scheduled=true;
            if(stage.isAttachedToWindow())stage.postOnAnimation(animationFlush);
            else ui.post(animationFlush);
        }
        void onPreDraw(){
            if(dirty&&!isDestroyed()&&!isFinishing())pushNow();
        }
        void flushForCapture(){
            if(isDestroyed()||isFinishing())return;
            // Native captures use the clean plane; the separate floating plane stays visible.
            pushNow();
        }
        void clear(){
            frame=null;
            onTokenAvailable();
            pendingLoadNotice=null;
            dirty=false;
            if(token!=null)NativeGame.pushMessagePlane(token.value(),null,new int[6],false);
        }
        void release(){
            if(stage!=null)stage.removeCallbacks(animationFlush);
            ui.removeCallbacks(animationFlush);
            ui.removeCallbacks(retryRaster);
            if(token!=null)token.close();
            pendingLoadNotice=null;textureReset();
            token=null;owner=null;frame=null;bitmap=null;canvas=null;floatingBitmap=null;floatingCanvas=null;dirty=false;scheduled=false;
            rasterFailures=0;rasterFailed=false;
        }
        private void ensureLaidOut(View view){
            if(view==null||view.getVisibility()==View.GONE)return;
            if(view.getWidth()!=width||view.getHeight()!=height||view.isLayoutRequested()){
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
                view.layout(0,0,width,height);
            }
        }
        private void pushNow(){
            onTokenAvailable();
            dirty=false;
            if(token==null||rasterFailed)return;
            boolean storySession=!launchingGame&&titlePanel==null&&moviePlayback==null&&movieErrorPanel==null&&!storyMovieExitActive;
            GameRunner.Frame target=frame;
            if(!storySession||target==null||target.wait==NativeGame.END){
                NativeGame.pushMessagePlane(token.value(),null,new int[6],false);
                return;
            }
            if(!token.acceptsFrame(target.generation))return;
            int choicePage=choices==null?0:choices.page();
            int[] key=DialogueLayout.composePlaneKey(target.planeKey,uiHidden,choicePage);
            boolean drawDialogue=!uiHidden&&dialoguePanel!=null&&dialoguePanel.getVisibility()!=View.GONE;
            boolean drawChoices=!uiHidden&&choices!=null&&choices.getVisibility()!=View.GONE;
            if(!drawDialogue&&!drawChoices){
                if(target.wait==NativeGame.DIALOGUE&&target.displayBody.glyphCount==0&&!uiHidden)
                    key[5]|=DialogueLayout.PLANE_TEXT_COMPLETE;
                NativeGame.pushMessagePlane(token.value(),null,key,!uiHidden);
                return;
            }
            boolean liveCursor=panelsOpen==0&&preparingPanel==null&&!uiHidden;
            try{
                int w=Math.max(1,width),h=Math.max(1,height);
                if(bitmap==null||bitmap.getWidth()!=w||bitmap.getHeight()!=h){
                    bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                    canvas=new Canvas(bitmap);
                }else bitmap.eraseColor(Color.TRANSPARENT);
                if(drawDialogue){
                    ensureLaidOut(dialoguePanel);
                    dialoguePanel.drawPlane(canvas,false);
                }
                if(drawChoices){
                    ensureLaidOut(choices);
                    choices.drawPlane(canvas,false);
                }
                boolean drawFloating=liveCursor&&(drawDialogue&&dialoguePanel.waitingCursorVisible()||
                    drawChoices&&choices.hasFloating());
                if(drawFloating){
                    if(floatingBitmap==null||floatingBitmap.getWidth()!=w||floatingBitmap.getHeight()!=h){
                        floatingBitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                        floatingCanvas=new Canvas(floatingBitmap);
                    }else floatingBitmap.eraseColor(Color.TRANSPARENT);
                    if(drawDialogue)dialoguePanel.drawFloating(floatingCanvas);
                    if(drawChoices)choices.drawFloating(floatingCanvas);
                }
                // A completion acknowledgement describes the pixels just drawn, not the worker's reveal budget.
                if(target.wait==NativeGame.DIALOGUE&&drawDialogue&&!uiHidden&&dialoguePanel.textComplete())
                    key[5]|=DialogueLayout.PLANE_TEXT_COMPLETE;
                NativeGame.pushStoryPlanes(token.value(),bitmap,drawFloating?floatingBitmap:null,key,!uiHidden);
                rasterFailures=0;ui.removeCallbacks(retryRaster);
                if(liveCursor&&(drawDialogue&&dialoguePanel.portraitAnimating()||drawChoices&&choices.animating()))schedulePush();
            }catch(RuntimeException|OutOfMemoryError e){
                Log.w("SystemNNN","Unable to rasterize story UI plane",e);
                ui.removeCallbacks(retryRaster);
                if(++rasterFailures<3)ui.postDelayed(retryRaster,Math.max(16,target.frameMs));
                else{
                    rasterFailed=true;
                    if(owner!=null)owner.storyPlaneFailed(target.generation,e);
                }
            }
        }
    }
    private boolean acceptPreparedPanel(GameUiPanel panel,GameRunner owner){
        if(preparingPanel!=panel)return false;
        preparingPanel=null;
        if(owner==runner&&!isDestroyed()&&!isFinishing()&&moviePlayback==null)return true;
        panel.discardBackground();
        if(owner==runner&&!isDestroyed()&&!isFinishing()&&panelsOpen==0)owner.menu(false);
        return false;
    }
    private void mountOriginalPanel(GameUiPanel panel,Runnable back) {
        if(quickNoticeView!=null)quickNoticeView.clear();
        if(gamePanel!=null)stage.removeView(gamePanel);else panelsOpen++;
        for(int i=0;i<stage.getChildCount();i++)stage.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        gamePanel=panel;if(!isModalPanel(panel))visibleModal=null;
        panel.canvas(width,height);panelBack=back;stage.addView(panel,new FrameLayout.LayoutParams(-1,-1));
        panel.sound((se,voice)->{if(runner!=null)runner.playClick(se,voice);});panel.uiActive(foreground);
        updateVisibility();immersive();
    }
    private void discardPanelPreparation() {
        if(preparingPanel==null)return;
        preparingPanel.discardBackground();
        preparingPanel=null;
        if(isOverlayMode(gamePanel)&&modeUnderPanel!=null){
            lockModalOverlay(gamePanel,false);modeUnderPanel=null;
        }
    }
    private void closeOriginalPanel() {
        closeOriginalPanel(true);
    }
    private void closeOriginalPanel(boolean resume) {
        panelWarmup=null;
        discardPanelPreparation();
        if(gamePanel==null)return;
        stage.removeView(gamePanel);gamePanel=null;gameSlots=null;panelBack=null;visibleModal=null;
        updatePanelAction();
        for(int i=0;i<stage.getChildCount();i++)stage.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        panelsOpen=Math.max(0,panelsOpen-1);
        if(resume&&runner!=null&&panelsOpen==0){
            if(resumeModeAfterPanel(null))return;
            if(currentFrame!=null&&currentFrame.wait==NativeGame.MODAL){showScriptModal(null);return;}
            if(currentFrame!=null&&currentFrame.wait==NativeGame.PLACE){showPlace(null);return;}
            runner.menu(false);
        }
        updateVisibility();updateStatus();immersive();
    }
    private void leaveOriginalPanel(GameUiAssets.Config config){
        GameUiPanel panel=gamePanel;
        if(panel==null||runner==null)return;
        panel.leave(config,false,(picture,filled)->{
            if(panel!=gamePanel||isDestroyed()||isFinishing())return;
            if(resumeModeAfterPanel(picture))return;
            if(titlePanel==null){
                boolean childPanel=config==uiAssets.config||config==uiAssets.save||config==uiAssets.load;
                enterStory(picture,filled&&childPanel);return;
            }
            if(galleryPage!=null){++galleryRequest;galleryPage=null;runner.closeGallery();}
            titlePrevious=picture;returningToTitle=true;
            runner.reenterTitle();
        });
        updatePanelActionEnabled();
    }
    private void enterStory(Bitmap previous){enterStory(previous,false);}
    private void enterStory(Bitmap previous,boolean filledChildPanel){
        if(currentFrame!=null&&currentFrame.wait==NativeGame.NATIVE_PANEL){
            closeOriginalPanel(false);runner.menu(false);return;
        }
        if(currentFrame!=null&&currentFrame.wait==NativeGame.MODAL){closeOriginalPanel(false);showScriptModal(previous);return;}
        if(currentFrame!=null&&currentFrame.wait==NativeGame.PLACE){closeOriginalPanel(false);showPlace(previous);return;}
        if(previous==null||currentFrame==null){closeOriginalPanel(false);runner.menu(false);return;}
        boolean choice=currentFrame.wait==NativeGame.CHOICE;
        GameUiAssets.Config config=choice?uiAssets.selection:uiAssets.printMessage;
        if(choice&&filledChildPanel&&config.number("enterFadeInType",0)==0)config=uiAssets.printMessage;
        UiOpening opening=new UiOpening(config,currentFrame.frameMs,false);
        if(!opening.hasFade()){closeOriginalPanel(false);runner.menu(false);return;}
        if(opening.needsWipeTarget()){
            storyPlane.flushForCapture();
            GameRunner owner=runner;GameUiPanel panel=gamePanel;
            owner.capture(null,true,true,(picture,ignored)->runOnUiThread(()->{
                if(owner==runner&&panel==gamePanel&&!isFinishing()&&!isDestroyed())finishEnteringStory(opening,previous,picture);
            }));
        }else finishEnteringStory(opening,previous,null);
    }
    private void finishEnteringStory(UiOpening opening,Bitmap previous,GameRunner.Capture picture){
        closeOriginalPanel(false);
        if(picture!=null)opening.target(snapshotStory(picture,!opening.delaysControls()));
        sceneEntrance=new SceneEntrance(opening,previous);
        stage.addView(sceneEntrance,new FrameLayout.LayoutParams(-1,-1));
        placeIndependentStatus();
        runner.resumeScriptAudio();
    }
    private void showScriptModal(Bitmap previous){
        GameRunner.Modal modal=currentFrame==null?null:currentFrame.modal;
        showModePanel(modal,previous);
    }
    private static boolean isModalPanel(GameUiPanel panel){
        return panel instanceof GameInputPanel||panel instanceof GameReferenceSelectionPanel||
            panel instanceof GameOptionalCatalogPanel||panel instanceof GameProgressViewsPanel||panel instanceof GamePlacePanel||
            isOverlayMode(panel)||panel instanceof GameInventoryPanel||panel instanceof GameStatusPartyPanel;
    }
    private static boolean isOverlayMode(GameUiPanel panel){
        return panel instanceof GameAdvancedSelectionPanel||panel instanceof GameChartPanel;
    }
    private static void lockModalOverlay(GameUiPanel panel,boolean locked){
        if(panel instanceof GameAdvancedSelectionPanel)((GameAdvancedSelectionPanel)panel).overlayInput(locked);
        else if(panel instanceof GameChartPanel)((GameChartPanel)panel).overlayInput(locked);
    }
    private void overlayModal(Runnable action){
        if(!isOverlayMode(gamePanel)||visibleModal==null)return;
        GameUiPanel panel=gamePanel;
        GameRunner.Modal modal=visibleModal;
        GameRunner owner=runner;
        GameRunner.Frame frame=currentFrame;
        lockModalOverlay(panel,true);
        if(panel instanceof GameChartPanel&&owner!=null){
            ((GameChartPanel)panel).captureSave(picture->{
                if(panel!=gamePanel||owner!=runner||modal!=visibleModal||isDestroyed()||isFinishing()){
                    if(picture!=null)picture.recycle();
                    if(panel==gamePanel)lockModalOverlay(panel,false);
                    return;
                }
                GameRunner.Capture capture=null;
                if(picture!=null)try{
                    int w=DialogueLayout.clamp(uiAssets.dataFile.number("picSizeX",uiAssets.dataFile.number("areaSizeX",132)),1,512);
                    int h=DialogueLayout.clamp(uiAssets.dataFile.number("picSizeY",uiAssets.dataFile.number("areaSizeY",100)),1,512);
                    Bitmap thumbnail=Bitmap.createScaledBitmap(picture,w,h,true);
                    int[] pixels=new int[w*h];thumbnail.getPixels(pixels,0,w,0,0,w,h);
                    capture=new GameRunner.Capture(pixels,w,h);
                    if(thumbnail!=picture)thumbnail.recycle();
                }catch(RuntimeException|OutOfMemoryError error){Log.w("SystemNNN","Chart save thumbnail unavailable",error);}
                finally{picture.recycle();}
                owner.chartSavePicture(frame,modal,capture,accepted->runOnUiThread(()->{
                    if(panel!=gamePanel||owner!=runner||modal!=visibleModal||isDestroyed()||isFinishing()){
                        if(panel==gamePanel)lockModalOverlay(panel,false);
                        return;
                    }
                    if(!accepted){lockModalOverlay(panel,false);return;}
                    modeUnderPanel=modal;action.run();
                }));
            });
        }else{modeUnderPanel=modal;action.run();}
    }
    private boolean resumeModeAfterPanel(Bitmap previous){
        GameRunner.Modal pending=modeUnderPanel;
        if(pending==null||runner==null)return false;
        modeUnderPanel=null;
        GameRunner owner=runner;
        owner.actModal(pending,GameRunner.Modal.RESUME,0,update->runOnUiThread(()->{
            if(owner!=runner||isDestroyed()||isFinishing())return;
            owner.resumeScriptAudio();
            if(update.status<0){
                if(update.modal!=null)showModePanel(update.modal,previous);else enterStory(previous);
            }
            else if(update.status==0)showModePanel(update.modal,previous);
            else finishModePanel(owner,update,previous);
        }));
        return true;
    }
    private void openMenuMode(int mode,int caller){
        GameRunner owner=runner;
        GameUiPanel before=gamePanel;
        if(owner==null||titlePanel==null)return;
        owner.openMenuModal(mode,caller,modal->runOnUiThread(()->{
            if(modal==null||owner!=runner||before!=gamePanel||isDestroyed()||isFinishing())return;
            if(modal.problem!=null||before==null||galleryPage==null){showModePanel(modal,null);return;}
            before.leave(galleryPage.config,false,picture->{
                if(owner==runner&&before==gamePanel&&!isDestroyed()&&!isFinishing())showModePanel(modal,picture);
            });
        }));
    }
    private void showModePanel(GameRunner.Modal modal,Bitmap previous){
        GameRunner owner=runner;
        if(owner==null||uiAssets==null||modal==null)return;
        if(modal.problem!=null){abortModePanel(modal,previous);return;}
        visibleModal=modal;
        GameUiPanel panel;
        if(modal.textInput()){
            panel=new GameInputPanel(this,uiAssets,modal.assets.config,modal.assets.images,modal.decoder,modal.mode,
                width,height,modal.arguments,modal.byteLimits,modal.text,this::systemClick,(accepted,elapsed,values,picture)->{
                    GameUiPanel active=gamePanel;
                    if(!(active instanceof GameInputPanel)||owner!=runner||visibleModal!=modal)return;
                    owner.completeModal(modal,accepted,elapsed,values,update->runOnUiThread(()->{
                        if(active!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                        if(update.status<0){active.cancelLeave();return;}
                        finishModePanel(owner,update,picture);
                    }));
                });
        }else if(modal.catalog()){
            panel=new GameOptionalCatalogPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel);
        }else if(modal.progressView()){
            panel=new GameProgressViewsPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel);
        }else if(modal.inventory()){
            panel=new GameInventoryPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel);
        }else if(modal.statusView()){
            panel=new GameStatusPartyPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel);
        }else if(modal.mode==GameRunner.Modal.SELECT_PLACE2){
            panel=new GamePlacePanel(this,uiAssets,currentFrame,this::systemClick,this::showMenu,
                place->actModePanel(GameRunner.Modal.CHOOSE,place));
        }else if(modal.mode==GameRunner.Modal.SELECT_CHART){
            boolean options=titlePanel==null&&(currentFrame==null||currentFrame.optionsAllowed);
            panel=new GameChartPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel,
                options?()->overlayModal(this::showMenu):null);
        }else if(modal.advancedSelection()){
            GameAdvancedSelectionPanel selection=new GameAdvancedSelectionPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel);
            boolean object=modal.mode==GameRunner.Modal.SELECT_OBJECT;
            boolean options=currentFrame==null||currentFrame.optionsAllowed;
            selection.optionActions(options?()->overlayModal(this::showMenu):null,
                options&&object?()->overlayModal(this::showSettings):null,
                object&&titlePanel==null&&modal.assets.config.number("backLogOk",0)!=0?()->overlayModal(this::showBacklog):null);
            panel=selection;
        }else{
            panel=new GameReferenceSelectionPanel(this,uiAssets,modal,width,height,this::systemClick,this::actModePanel);
        }
        if(modal.mode==GameRunner.Modal.SELECT_ZUKAN)panel.onBackdropFailure(error->{
            if(owner==runner&&panel==gamePanel&&visibleModal!=null)
                abortModePanel(visibleModal,"Cannot render reference detail: "+error.getMessage(),null);
        });
        else if(modal.mode==GameRunner.Modal.SELECT_PLACE2)panel.onBackdropFailure(error->{
            if(owner==runner&&panel==gamePanel&&visibleModal==modal)
                abortModePanel(modal,"Cannot render place background: "+error.getMessage(),null);
        });
        if(previous==null)showOriginalPanel(panel,null);
        else {owner.menu(true);panel.previous(previous);mountOriginalPanel(panel,null);}
    }
    private void actModePanel(int action,int value){
        GameUiPanel active=gamePanel;
        GameRunner owner=runner;
        GameRunner.Modal request=visibleModal;
        if(!isModalPanel(active)||owner==null||request==null)return;
        if((request.mode==GameRunner.Modal.SELECT_OBJECT||request.mode==GameRunner.Modal.SELECT_PLACE2)&&
            action==GameRunner.Modal.CHOOSE){
            active.leave(request.assets.config,false,picture->{
                if(active!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                owner.actModal(request,action,value,update->runOnUiThread(()->{
                    if(active!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                    if(update.status<0)active.cancelLeave();else finishModePanel(owner,update,picture);
                }));
            });
            return;
        }
        owner.actModal(request,action,value,update->runOnUiThread(()->{
            if(active!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
            if(update.status<0){active.cancelLeave();return;}
            if(update.status==GameRunner.Modal.LEAVE_READY){
                commitChartAfterLeave((GameChartPanel)active,owner,request);
                return;
            }
            if(update.status==0){
                if(update.modal.problem!=null){abortModePanel(update.modal,null);return;}
                visibleModal=update.modal;
                if(active instanceof GameOptionalCatalogPanel)((GameOptionalCatalogPanel)active).update(visibleModal);
                else if(active instanceof GameProgressViewsPanel)((GameProgressViewsPanel)active).update(visibleModal);
                else if(active instanceof GameAdvancedSelectionPanel)((GameAdvancedSelectionPanel)active).update(visibleModal);
                else if(active instanceof GameInventoryPanel)((GameInventoryPanel)active).update(visibleModal);
                else if(active instanceof GameStatusPartyPanel)((GameStatusPartyPanel)active).update(visibleModal);
                else if(active instanceof GameChartPanel)((GameChartPanel)active).update(visibleModal);
                else ((GameReferenceSelectionPanel)active).update(visibleModal);
                return;
            }
            active.leave(request.assets.config,false,picture->{
                if(active==gamePanel&&owner==runner&&!isDestroyed()&&!isFinishing())finishModePanel(owner,update,picture);
            });
        }));
    }
    private void commitChartAfterLeave(GameChartPanel panel,GameRunner owner,GameRunner.Modal request){
        panel.leave(request.assets.config,false,picture->{
            if(panel!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
            owner.actModal(request,GameRunner.Modal.COMMIT,0,update->runOnUiThread(()->{
                if(panel!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                if(update.status==0&&update.modal!=null){
                    if(update.modal.problem!=null){abortModePanel(update.modal,picture);return;}
                    visibleModal=update.modal;
                    panel.update(visibleModal);
                    panel.cancelLeave();
                }else if(update.status<0)panel.cancelLeave();
                else finishModePanel(owner,update,picture);
            }));
        });
    }
    private void abortModePanel(GameRunner.Modal modal,Bitmap previous){
        abortModePanel(modal,modal.problem,previous);
    }
    private void abortModePanel(GameRunner.Modal modal,String reason,Bitmap previous){
        GameRunner owner=runner;
        owner.abortModal(modal,reason,update->runOnUiThread(()->{
            if(owner==runner&&!isDestroyed()&&!isFinishing()&&update.status>=0)finishModePanel(owner,update,previous);
        }));
    }
    private void finishModePanel(GameRunner owner,GameRunner.ModalUpdate update,Bitmap picture){
        if(update.modal!=null){showModePanel(update.modal,picture);return;}
        if(update.returnMode==GameRunner.Modal.OMAKE){
            optionalGalleryPrevious=picture;
            if(titlePanel==null){openGalleryAfterTitle=true;owner.returnToTitle();}
            else openGallery(GalleryCatalog.Screen.OMAKE,0,0,-1);
        }else if(update.returnMode==GameRunner.Modal.TITLE){
            titlePrevious=picture;returningToTitle=true;
            if(titlePanel==null)owner.returnToTitle();else owner.reenterTitle();
        }else if(update.wait==NativeGame.MOVIE||update.wait==NativeGame.TITLE){
            closeOriginalPanel(false);
            owner.menu(false);
        }else enterStory(picture);
    }
    private void showPlace(Bitmap previous){
        GameRunner owner=runner;
        if(owner==null||uiAssets==null||currentFrame==null||currentFrame.wait!=NativeGame.PLACE)return;
        GameUiAssets.Config config=uiAssets.place(currentFrame.placeMode);
        GamePlacePanel panel=new GamePlacePanel(this,uiAssets,currentFrame,this::systemClick,this::showMenu,place->{
            GameUiPanel active=gamePanel;
            if(!(active instanceof GamePlacePanel))return;
            active.leave(config,false,picture->{
                if(active!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                owner.choosePlace(place,success->runOnUiThread(()->{
                    if(active!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                    if(success)enterStory(picture);else active.cancelLeave();
                }));
            });
        });
        if(previous==null)showOriginalPanel(panel,null);
        else {owner.menu(true);panel.previous(previous);mountOriginalPanel(panel,null);}
    }
    private final class SceneEntrance extends View {
        private final UiOpening opening;
        private final GameRunner owner;
        SceneEntrance(UiOpening opening,Bitmap previous){
            super(PlayerActivity.this);this.opening=opening;owner=runner;opening.previous(previous);setClickable(true);
        }
        void skip(){opening.skip();invalidate();}
        @Override protected void onDetachedFromWindow(){opening.close();super.onDetachedFromWindow();}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(opening.touch(event))invalidate();
            return true;
        }
        @Override protected void onDraw(Canvas canvas){
            opening.begin();
            if(opening.overlay(canvas,this,width,height))postInvalidateOnAnimation();
            else if(sceneEntrance==this){
                sceneEntrance=null;
                setVisibility(INVISIBLE);
                if(owner==runner&&!isDestroyed()&&!isFinishing())owner.menu(false);
                FrameLayout container=stage;
                // Defer the child-list mutation until this draw traversal is done.
                container.post(()->{
                    if(getParent()==container)container.removeView(this);
                    placeIndependentStatus();
                });
            }
        }
    }
    private void showOriginalMenu() {
        GameUiAssets.Config cfg=uiAssets.system;
        boolean scripted=cfg.number("backScriptFlag",0)!=0;
        int backgroundType=scripted?3:cfg.number("backScreenType",2);
        GameSystemMenuPanel panel=new GameSystemMenuPanel(this,uiAssets,backgroundType==1?cfg.value("backScreenFileName",null):null,this::systemClick);
        if(backgroundType!=1)panel.transparentBackground();
        panel.opening(cfg,true);
        boolean capture=backgroundType==2||backgroundType==3&&cfg.number("screenToGetPutFlag",0)!=0;
        if(!capture)panel.uiBackground(cfg,13,1);
        String base=cfg.value("filenameMenuBase","");
        Bitmap baseImage=uiAssets.image(base);
        if(baseImage!=null)panel.picture(base,cfg.number("basePrintX",0),cfg.number("basePrintY",0),baseImage.getWidth(),baseImage.getHeight());
        String[] keys={"saveButton","loadButton","configButton","windowButton","messageSkipButton","backlogButton","autoModeButton","cancelButton","exitButton"};
        String[] labels={"Save","Load","Settings","Hide Dialogue","Skip","Backlog","Auto","Resume","Title Menu"};
        int[] ys={67,115,163,259,307,211,357,404,525};
        Runnable back=()->leaveOriginalPanel(cfg);
        Runnable[] actions={()->showSlots(true),()->showSlots(false),this::showSettings,
            ()->{closeOriginalPanel();hideUi();},()->{closeOriginalPanel();runner.skip(true);},this::showBacklog,
            ()->{closeOriginalPanel();runner.auto(true);},back,()->confirmOriginalExit(panel)};
        boolean replay=inSceneReplay();
        Set<Integer> standardSlots=new HashSet<>();
        for(int i=0;i<keys.length;i++){
            int n=cfg.number(keys[i],i+1);if(n<=0||i==5&&choices!=null&&!choices.backlogAllowed())continue;
            if(currentFrame!=null&&currentFrame.wait==NativeGame.NATIVE_PANEL&&(i==3||i==4||i==5||i==6))continue;
            if(currentFrame!=null&&currentFrame.placeMode!=0&&
                (i==3||i==4||i==6||i==5&&uiAssets.place(currentFrame.placeMode).number("backLogOk",0)==0))continue;
            if(modeUnderPanel!=null&&(i==3||i==4||i==6||i==5&&modeUnderPanel.assets.config.number("backLogOk",0)==0))continue;
            GameButton button=panel.standardButton(cfg,i,replay,"Menu1Button"+n,"Menu1Button1",labels[i],actions[i],940,ys[i],320,72);
            standardSlots.add(n);
            if(i==0&&!replay)button.prepare(()->warmStoryPanel(GameStoryControlsModel.Action.SAVE));
            if(i==1&&!replay)button.prepare(()->warmStoryPanel(GameStoryControlsModel.Action.LOAD));
            if(i==2)button.prepare(()->warmStoryPanel(GameStoryControlsModel.Action.CONFIG));
        }
        if(currentFrame==null||currentFrame.wait!=NativeGame.NATIVE_PANEL)
            panel.addExtendedModes(standardSlots,GameRunner.Modal::systemMenuMode,mode->!replay&&runner!=null&&runner.systemMenuModeEnabled(mode),
                mode->openSystemMenuMode(panel,mode));
        showOriginalPanel(panel,back,capture?cfg:null);
        if(runner!=null)runner.suspendScriptAudio();
    }
    private void openSystemMenuMode(GameSystemMenuPanel menu,int mode){
        GameRunner owner=runner;
        if(owner==null||menu!=gamePanel||menu.preparingExtension())return;
        menu.preparingExtension(true);
        owner.openSystemMenuModal(mode,modal->runOnUiThread(()->{
            menu.preparingExtension(false);
            if(owner!=runner||menu!=gamePanel||isDestroyed()||isFinishing()||modal==null)return;
            modeUnderPanel=null;
            if(modal.problem!=null){abortModePanel(modal,null);return;}
            menu.leave(uiAssets.system,false,picture->{
                if(owner==runner&&menu==gamePanel&&!isDestroyed()&&!isFinishing())showModePanel(modal,picture);
            });
        }));
    }
    private void confirmOriginalExit(GameSystemMenuPanel panel) {
        if(gamePanel!=panel||runner==null)return;
        Runnable confirm=()->{
            if(inSceneReplay()){
                GameRunner owner=runner;
                panel.leave(uiAssets.system,true,picture->{
                    if(owner!=runner||panel!=gamePanel||isDestroyed()||isFinishing())return;
                    optionalGalleryPrevious=picture;owner.endGalleryReplay();
                });
                return;
            }
            if(returningToTitle)return;
            panel.leave(uiAssets.system,true,picture->{
                if(isDestroyed()||isFinishing()||gamePanel!=panel||runner==null)return;
                titlePrevious=picture;returningToTitle=true;
                runner.returnToTitle();
            });
        };
        if(inSceneReplay()||uiAssets.system.number("warningPrintFlag",1)==0){confirm.run();return;}
        panel.confirmExit(confirm,()->leaveOriginalPanel(uiAssets.system));
    }
    private LinearLayout column() {LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(8),dp(18),dp(14));return box;}
    private void showSettings() {
        if(uiAssets!=null&&(uiAssets.config.has("modename")||uiAssets.settingsModel(width,height).original)){
            Runnable back=()->leaveOriginalPanel(uiAssets.config);
            showOriginalPanel(new GameSettingsPanel(this,uiAssets,preferences,runner,this::systemClick,back,width,height,runner.configMask(),takePanelWarmup(),settingsPage,page->settingsPage=page),back);return;
        }
        LinearLayout box=column();
        TextView hint=label("Changes take effect immediately and persist across sessions.",14);hint.setPadding(0,0,0,dp(10));box.addView(hint);
        volumeSetting(box,"Music",PlayerSettings.MUSIC,settings.musicVolume);
        volumeSetting(box,"Sound",PlayerSettings.SOUND,settings.soundVolume);
        volumeSetting(box,"Voice",PlayerSettings.VOICE,settings.voiceVolume);
        volumeSetting(box,"Effect Voice",PlayerSettings.EFFECT_VOICE,settings.effectVoiceVolume);
        choiceSetting(box,"Text Speed",PlayerSettings.TEXT_SPEED,new String[]{"Slow","Normal","Fast","Instant"},settings.textSpeed,false);
        choiceSetting(box,"Auto Speed",PlayerSettings.AUTO_SPEED,new String[]{"Slow","Normal","Fast"},settings.autoSpeed,false);
        choiceSetting(box,"Skip Scope",PlayerSettings.SKIP_READ_ONLY,new String[]{"Read Only","All Text"},settings.skipReadOnly?0:1,true);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Settings").setView(scroll)
            .setNeutralButton("Text & Font",null).setPositiveButton("Done",dialogClick(null)).create();
        showPanel(dialog);
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v->showReadingSettings());
        if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(dp(760),root.getWidth()-dp(32)),Math.max(dp(200),root.getHeight()-dp(32)));
    }
    private void showReadingSettings() {
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final AlertDialog[] holder=new AlertDialog[1];
        content.addView(EmberfallUi.buildModalHeader(this,"Text & Font",()->{
            if(holder[0]!=null)holder[0].dismiss();
        }));

        TextView sizeLabel=EmberfallUi.text(this,"Text Size",12.5f,EmberfallUi.TEXT_SECONDARY,true);
        LinearLayout.LayoutParams sizeLabelLp=new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sizeLabelLp.topMargin=dp(14);
        content.addView(sizeLabel,sizeLabelLp);

        LinearLayout sizeSelector=EmberfallUi.buildReadingSizeSelector(this,settings.readingSize,size->{
            userPreferences.edit().putInt(PlayerSettings.READING_SIZE,size).apply();
        });
        LinearLayout.LayoutParams selectorLp=new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        selectorLp.topMargin=dp(8);
        content.addView(sizeSelector,selectorLp);

        LinearLayout fontCard=new LinearLayout(this);
        fontCard.setOrientation(LinearLayout.VERTICAL);
        fontCard.setBackground(EmberfallUi.roundedRect(EmberfallUi.BG_CARD,EmberfallUi.BORDER_CARD,dp(1),dp(12)));
        fontCard.setPadding(dp(12),dp(10),dp(12),dp(10));
        LinearLayout.LayoutParams fontCardLp=new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        fontCardLp.topMargin=dp(16);

        LinearLayout fontHeader=new LinearLayout(this);
        fontHeader.setOrientation(LinearLayout.HORIZONTAL);
        fontHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView fontLabel=EmberfallUi.text(this,"Font",12.5f,EmberfallUi.TEXT_SECONDARY,true);
        fontHeader.addView(fontLabel);

        final TextView fontNameView=EmberfallUi.text(this,PlayerFont.currentName(this),13f,EmberfallUi.TEXT_PRIMARY,true);
        fontNameView.setSingleLine(true);
        fontNameView.setEllipsize(TextUtils.TruncateAt.END);
        fontNameView.setGravity(Gravity.END);
        LinearLayout.LayoutParams fontNameLp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);
        fontNameLp.setMarginStart(dp(12));
        fontHeader.addView(fontNameView,fontNameLp);

        fontCard.addView(fontHeader,new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout fontActions=new LinearLayout(this);
        fontActions.setOrientation(LinearLayout.HORIZONTAL);
        fontActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fontActionsLp=new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        fontActionsLp.topMargin=dp(10);

        TextView chooseBtn=EmberfallUi.secondaryButton(
            this,
            "Choose Font",
            EmberfallUi.fontIcon(this,15,EmberfallUi.ACCENT),
            v->pickFontFile()
        );
        fontActions.addView(chooseBtn,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));

        final TextView[] resetBtnHolder=new TextView[1];
        final Runnable refreshUi=()->{
            fontNameView.setText(PlayerFont.currentName(PlayerActivity.this));
            if(resetBtnHolder[0]!=null){
                resetBtnHolder[0].setVisibility(PlayerFont.hasCustom(PlayerActivity.this)?View.VISIBLE:View.GONE);
            }
        };

        TextView resetBtn=EmberfallUi.dangerButton(this,"Reset",v->{
            PlayerFont.reset(PlayerActivity.this);
            Toast.makeText(PlayerActivity.this,"Font reset to default",Toast.LENGTH_SHORT).show();
            if(dialoguePanel!=null){dialoguePanel.refreshFont();storyPlane.schedulePush();}
            if(gamePanel!=null)gamePanel.postInvalidate();
            refreshUi.run();
        });
        resetBtnHolder[0]=resetBtn;
        LinearLayout.LayoutParams resetLp=new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        resetLp.setMarginStart(dp(8));
        fontActions.addView(resetBtn,resetLp);

        fontCard.addView(fontActions,fontActionsLp);
        content.addView(fontCard,fontCardLp);

        LinearLayout footer=new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        LinearLayout.LayoutParams footerLp=new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        footerLp.topMargin=dp(16);
        footer.addView(EmberfallUi.primaryButton(this,"Done",null,v->{
            if(holder[0]!=null)holder[0].dismiss();
        }));
        content.addView(footer,footerLp);

        refreshUi.run();
        final AlertDialog dialog=EmberfallUi.createModalDialog(this,content,true,480);
        holder[0]=dialog;
        refreshReadingSettingsFont=refreshUi;
        trackModalDialog(dialog,()->{
            if(refreshReadingSettingsFont==refreshUi){
                refreshReadingSettingsFont=null;
            }
        });
        dialog.show();
    }
    private void volumeSetting(LinearLayout box,String title,String key,int current) {
        TextView value=label(title+"  "+current+"%",15);box.addView(value);
        SeekBar slider=new SeekBar(this);slider.setMax(100);slider.setProgress(current);slider.setContentDescription(title+" Volume");slider.setSoundEffectsEnabled(false);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar seek,int progress,boolean user){value.setText(title+"  "+progress+"%");if(user)preferences.edit().putInt(key,progress).apply();}
            public void onStartTrackingTouch(SeekBar seek){systemClick();}public void onStopTrackingTouch(SeekBar seek){}
        });box.addView(slider,new LinearLayout.LayoutParams(-1,dp(40)));
    }
    private void choiceSetting(LinearLayout box,String title,String key,String[] labels,int selected,boolean bool) {
        TextView heading=label(title,15);heading.setPadding(0,dp(8),0,0);box.addView(heading);
        RadioGroup group=new RadioGroup(this);group.setOrientation(LinearLayout.HORIZONTAL);
        for(int i=0;i<labels.length;i++){RadioButton button=new RadioButton(this);button.setId(View.generateViewId());button.setText(labels[i]);button.setTextColor(Color.WHITE);button.setTextSize(14);button.setSoundEffectsEnabled(false);button.setOnClickListener(clicked(null));button.setTag(i);group.addView(button);if(i==selected)button.setChecked(true);}
        group.setOnCheckedChangeListener((g,id) -> {View selectedView=g.findViewById(id);if(selectedView==null)return;int index=(Integer)selectedView.getTag();
            if(bool)preferences.edit().putBoolean(key,index==0).apply();else preferences.edit().putInt(key,index).apply();});
        box.addView(group);
    }
    private void showBacklog() {
        if(uiAssets==null||runner==null)return;
        if(choices!=null&&(choices.confirming()||!choices.backlogAllowed()))return;
        if(currentFrame!=null&&currentFrame.placeMode!=0&&uiAssets.place(currentFrame.placeMode).number("backLogOk",0)==0)return;
        GameUiAssets.Config cfg=uiAssets.backlog;
        int bgMode=GameBacklogPanel.bgMode(cfg);
        boolean capture=bgMode==2||bgMode==3&&cfg.number("screenToGetPutFlag",0)!=0;
        Runnable back=()->{if(runner!=null)runner.closeBacklog();leaveOriginalPanel(cfg);};
        showOriginalPanel(new GameBacklogPanel(this,uiAssets,dialogueStyle,width,height,back,
            name->{if(runner!=null)runner.replayBacklogVoice(name);},this::jumpBacklog),back,capture?cfg:null);
        runner.requestBacklog();
    }
    private void jumpBacklog(long id){
        GameUiPanel panel=gamePanel;
        GameRunner owner=runner;
        if(!(panel instanceof GameBacklogPanel)||owner==null)return;
        owner.backlogJumpCue();
        panel.leaveForBacklogJump(uiAssets.backlog,picture->{
            if(panel!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
            owner.jumpBacklog(id,success->runOnUiThread(()->{
                if(panel!=gamePanel||owner!=runner||isDestroyed()||isFinishing())return;
                if(success)enterStory(picture);else panel.cancelLeave();
            }));
        });
    }
    @Override public void backlog(List<GameRunner.BacklogLine> lines){runOnUiThread(()->{
        GameUiPanel panel=preparingPanel instanceof GameBacklogPanel?preparingPanel:gamePanel;
        if(panel instanceof GameBacklogPanel)((GameBacklogPanel)panel).entries(lines);
    });}
    private void showSlots(boolean saving) {
        if(saving&&currentFrame!=null&&currentFrame.wait==NativeGame.NATIVE_PANEL&&!currentFrame.nativePanelSavable)return;
        if(runner==null)return;savingSlots=saving;
        if(saving&&titlePanel==null)storyPlane.flushForCapture();
        if(uiAssets!=null){
            GameUiAssets.Config config=saving?uiAssets.save:uiAssets.load;
            Runnable back=()->leaveOriginalPanel(config);
            gameSlots=new GameSlotsPanel(this,uiAssets,saving,runner.slotEntries(),runner.lastSelectedSlot(),this::systemClick,back,slot->{
                if(saving)runner.saveSlot(slot);
                else{
                    GameUiPanel panel=gamePanel;
                    panel.leave(config,false,(picture,filled)->{
                        if(panel!=gamePanel||isDestroyed()||isFinishing())return;
                        runner.loadSlot(slot,success->runOnUiThread(()->{
                            if(panel!=gamePanel||isDestroyed()||isFinishing())return;
                            if(success)enterStory(picture,filled);else panel.cancelLeave();
                        }));
                    });
                }
            },takePanelWarmup());showOriginalPanel(gameSlots,back);return;
        }
        slotRows=column();ScrollView scroll=new ScrollView(this);scroll.addView(slotRows);
        AlertDialog.Builder slotDialog=new AlertDialog.Builder(this).setTitle(saving?"Save":"Load").setView(scroll).setNegativeButton("Back",dialogClick(null));
        slotsDialog=slotDialog.create();
        showPanel(slotsDialog);if(slotsDialog.getWindow()!=null)slotsDialog.getWindow().setLayout(Math.min(dp(850),root.getWidth()-dp(32)),Math.max(dp(200),root.getHeight()-dp(32)));
        slotsChanged();
    }
    @Override public void slotsChanged() {runOnUiThread(() -> {
        if(runner==null||isDestroyed()||isFinishing())return;
        if(gameSlots!=null&&(gamePanel==gameSlots||preparingPanel==gameSlots)){
            gameSlots.entries(runner.slotEntries());updatePanelAction();return;
        }
        if(slotRows==null || slotsDialog==null || !slotsDialog.isShowing())return;slotRows.removeAllViews();
        TextView hint=label(savingSlots?"Select a slot to save.":"Select a save to resume game.",14);hint.setPadding(0,0,0,dp(10));slotRows.addView(hint);
        for(SaveSlots.Entry entry:runner.slotEntries()){
            String summary=entry.displaySummary();
            String detail=entry.corrupt?"Corrupted Save":!entry.exists?"Empty Slot":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(entry.savedAt))+
                (summary.isEmpty()?"":"\n"+summary);
            Button button=new Button(this);button.setAllCaps(false);button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            button.setPadding(dp(14),dp(8),dp(14),dp(8));button.setText(entry.label()+"\n"+detail);
            button.setEnabled(savingSlots||entry.exists&&!entry.corrupt);
            button.setSoundEffectsEnabled(false);button.setOnClickListener(clicked(v -> {
                if(savingSlots && entry.exists){AlertDialog confirm=new AlertDialog.Builder(this).setTitle("Overwrite "+entry.label()+"?").setMessage(detail)
                    .setNegativeButton("Cancel",dialogClick(null)).setPositiveButton("Overwrite",dialogClick((d,which) -> runner.saveSlot(entry.slot))).create();showPanel(confirm);}
                else if(savingSlots)runner.saveSlot(entry.slot);
                else {runner.loadSlot(entry.slot);slotsDialog.dismiss();}
            }));slotRows.addView(button,new LinearLayout.LayoutParams(-1,-2));
        }
    });}
    @Override public void titleReturnCancelled(){runOnUiThread(()->{
        if(isDestroyed()||isFinishing())return;
        returningToTitle=false;
        titlePrevious=null;
        openGalleryAfterTitle=false;
        optionalGalleryPrevious=null;
        if(gamePanel instanceof GameSystemMenuPanel)gamePanel.cancelLeave();
        else if(gamePanel!=null)closeOriginalPanel();
        updateVisibility();
    });}
    @Override public void notice(String text) { runOnUiThread(() -> {
        if(!isDestroyed()) Toast.makeText(this,text,Toast.LENGTH_SHORT).show();
    }); }
    @Override public void progressCorrupt(){runOnUiThread(()->{
        GameRunner owner=runner;
        if(isDestroyed()||isFinishing()||owner==null)return;
        new AlertDialog.Builder(this).setTitle("Corrupted Global Progress")
            .setMessage("Readable global progress will be retained after isolation; if none is valid, a new one will be created. Story saves and quarantined files remain in the save directory.")
            .setCancelable(false)
            .setNegativeButton("Back",dialogClick((d,w)->finish()))
            .setPositiveButton("Isolate & Retry",dialogClick((d,w)->{if(owner==runner)owner.recoverCorruptProgress();}))
            .show();
    });}
    @Override public void error(String text) { runOnUiThread(() -> {
        if (isDestroyed() || isFinishing()) return;
        if(text.contains("EXT_START_UNRESOLVED")){
            new AlertDialog.Builder(this).setTitle("Failed to Resolve EXT Base Offset")
                .setMessage("This game requires an EXT save segment, but its base offset could not be resolved. Startup stopped. You can re-analyze and generate emberfall-system.cfg manually; if it still fails, executable rules must be updated.")
                .setNegativeButton("Back",dialogClick((d,w)->finish()))
                .setPositiveButton("Regenerate Config",dialogClick((d,w)->{
                    GameRunner owner=runner;
                    new Thread(()->{
                        try{
                            SaveLayoutConfig config=SaveLayoutConfig.open(game,true);
                            runOnUiThread(()->{
                                if(isDestroyed()||isFinishing()||owner!=runner)return;
                                if(config.extensionStartCode<0)error("EXT_START_UNRESOLVED");
                                else if(owner!=null)owner.restart(false);
                            });
                        }catch(IOException|RuntimeException failure){
                            Log.w("SystemNNN","Unable to regenerate game configuration",failure);
                            runOnUiThread(()->{if(!isDestroyed()&&!isFinishing())
                                Toast.makeText(this,"Configuration generation failed; check game folder and write permissions",Toast.LENGTH_LONG).show();});
                        }
                    },"nnn-config").start();
                })).setCancelable(false).show();
            return;
        }
        if(returningToTitle){returningToTitle=false;titlePrevious=null;closeOriginalPanel(false);}
        if (text.contains("spt/sys.spt") && (text.contains("SPT header") || text.contains("SPTHEADER0") || text.contains("Record length"))) {
            new AlertDialog.Builder(this).setTitle("Unsupported SystemNNN Script")
                .setMessage("spt/sys.spt in the selected directory failed format verification. Please ensure the game is fully extracted.")
                .setNegativeButton("Back",dialogClick((d,w) -> finish())).setPositiveButton("Choose Another Folder",dialogClick((d,w) -> {
                    startActivity(new Intent(this,PlayerActivity.class).setAction(Intent.ACTION_MAIN)); finish();
                })).show(); return;
        }
        GameRunner owner=runner;
        boolean startOver=owner!=null&&owner.initialized();
        new AlertDialog.Builder(this).setTitle("Game Execution Interrupted").setMessage(text)
            .setCancelable(false)
            .setNegativeButton(fromTyn?"Return to TYN":"Exit",dialogClick((d,w) -> finish()))
            .setPositiveButton(startOver?"Restart":"Retry Load",
                dialogClick((d,w) -> {if(owner!=null&&owner==runner)owner.restart(startOver);})).show();
    }); }
    private int dp(int n) {return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String s,int size) {
        TextView t=new TextView(this); t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP,size); t.setText(s); return t;
    }
    private void message(String title,String body,String action,View.OnClickListener listener) {
        if(launcherView!=null){launcherView.destroy();launcherView=null;}
        showingLauncher=false;
        root.removeAllViews();

        FrameLayout outer=new FrameLayout(this);
        outer.setBackgroundColor(EmberfallUi.BG_WINDOW);
        int outerPad=dp(20);
        outer.setPadding(outerPad,outerPad,outerPad,outerPad);

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        FrameLayout.LayoutParams scrollLp=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);
        outer.addView(scroll,scrollLp);

        FrameLayout centerWrap=new FrameLayout(this);
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(EmberfallUi.roundedRect(EmberfallUi.BG_SURFACE,EmberfallUi.BORDER_SUBTLE,dp(1),dp(16)));
        card.setPadding(dp(22),dp(20),dp(22),dp(20));

        TextView heading=EmberfallUi.text(this,title,18f,EmberfallUi.TEXT_PRIMARY,true);
        card.addView(heading);

        if(body!=null&&!body.isEmpty()){
            TextView description=EmberfallUi.text(this,body,13.5f,EmberfallUi.TEXT_SECONDARY,false);
            description.setLineSpacing(0f,1.25f);
            LinearLayout.LayoutParams descLp=new LinearLayout.LayoutParams(-1,-2);
            descLp.topMargin=dp(10);
            card.addView(description,descLp);
        }

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
        LinearLayout.LayoutParams actionsLp=new LinearLayout.LayoutParams(-1,-2);
        actionsLp.topMargin=dp(18);

        TextView back=EmberfallUi.secondaryButton(this,"Back",null,v->{
            if(!fromTyn&&runner==null){
                chooseHome(null);
            }else{
                finish();
            }
        });
        actions.addView(back);

        if(action!=null){
            TextView primary=EmberfallUi.primaryButton(this,action,null,v->{
                if(listener!=null)listener.onClick(v);
            });
            LinearLayout.LayoutParams primaryLp=new LinearLayout.LayoutParams(-2,-2);
            primaryLp.setMarginStart(dp(10));
            actions.addView(primary,primaryLp);
        }

        card.addView(actions,actionsLp);
        int cardMaxW=Math.min(dp(480),Math.max(dp(260),getResources().getDisplayMetrics().widthPixels-dp(48)));
        centerWrap.addView(card,new FrameLayout.LayoutParams(cardMaxW,-2,Gravity.CENTER));
        scroll.addView(centerWrap,new FrameLayout.LayoutParams(-1,-2));
        root.addView(outer,new FrameLayout.LayoutParams(-1,-1));
    }
    private void immersive() {
        View decor=getWindow().getDecorView();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller=decor.getWindowInsetsController();
            if(controller!=null){
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        }
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if(root!=null)root.requestApplyInsets();
    }
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        immersive();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if(focus){
            immersive();
            retryAudioFocus();
        }else clearCtrlSkip();
    }
    @Override public void onUserInteraction(){
        super.onUserInteraction();
        if(hasWindowFocus())retryAudioFocus();
    }
    private void retryAudioFocus(){
        if(!foreground)return;
        if(moviePlayback!=null)moviePlayback.retryFocus();
        else if(runner!=null)runner.windowFocused();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if(returningToTitle||preparingPanel!=null)return true;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){storyDownX=event.getRawX();storyDownY=event.getRawY();storyDrag=false;}
        if(storyPointerActive()){
            if((action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)&&
                Math.hypot(event.getRawX()-storyDownX,event.getRawY()-storyDownY)>storyTouchSlop)storyDrag=true;
            if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)storyPointer(event);
        }
        if(action==MotionEvent.ACTION_UP&&storyDrag&&!nativePanelActive()){
            storyDrag=false;
            MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);
            try{super.dispatchTouchEvent(cancel);}finally{cancel.recycle();}
            return true;
        }
        if(action==MotionEvent.ACTION_CANCEL)storyDrag=false;
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int key=event.getKeyCode();
        if(nativePanelActive()){
            int mode=key==KeyEvent.KEYCODE_L?5:key==KeyEvent.KEYCODE_S?6:key==KeyEvent.KEYCODE_C?12:
                key==KeyEvent.KEYCODE_M||key==KeyEvent.KEYCODE_O?13:0;
            if(mode!=0){
                if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)runner.nativePanelMenu(currentFrame,mode);
                return true;
            }
            if(key==KeyEvent.KEYCODE_ESCAPE){
                if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)nativePanelBack();
                return true;
            }
            if(key==KeyEvent.KEYCODE_CTRL_LEFT||key==KeyEvent.KEYCODE_CTRL_RIGHT)return true;
        }
        if(movieErrorPanel!=null){
            int direction=key==KeyEvent.KEYCODE_DPAD_DOWN?View.FOCUS_DOWN:key==KeyEvent.KEYCODE_DPAD_UP?View.FOCUS_UP:
                key==KeyEvent.KEYCODE_DPAD_LEFT?View.FOCUS_LEFT:key==KeyEvent.KEYCODE_DPAD_RIGHT?View.FOCUS_RIGHT:0;
            if(direction!=0){
                if(event.getAction()==KeyEvent.ACTION_DOWN){
                    View next=FocusFinder.getInstance().findNextFocus(movieErrorPanel,movieErrorPanel.findFocus(),direction);
                    if(next!=null)next.requestFocus();
                }
                return true;
            }
            if(key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_SPACE){
                movieErrorPanel.dispatchKeyEvent(event);return true;
            }
        }
        int bit=key==KeyEvent.KEYCODE_CTRL_LEFT?1:key==KeyEvent.KEYCODE_CTRL_RIGHT?2:0;
        if(bit!=0&&event.getAction()==KeyEvent.ACTION_DOWN&&storyPointerActive()){
            if(ctrlKeys==0)runner.ctrlSkip(true);
            ctrlKeys|=bit;
            return true;
        }
        if(bit!=0&&event.getAction()==KeyEvent.ACTION_UP&&(ctrlKeys&bit)!=0){
            ctrlKeys&=~bit;
            if(ctrlKeys==0&&runner!=null)runner.ctrlSkip(false);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private void clearCtrlSkip(){
        if(ctrlKeys==0)return;
        ctrlKeys=0;
        if(runner!=null)runner.ctrlSkip(false);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE&&storyPointerActive())storyPointer(event);
        return super.dispatchGenericMotionEvent(event);
    }
    private boolean storyPointerActive(){
        return runner!=null&&stage!=null&&titlePanel==null&&logoPanel==null&&panelsOpen==0&&moviePlayback==null&&movieErrorPanel==null&&!storyMovieExitActive;
    }
    private boolean nativePanelActive(){
        return currentFrame!=null&&currentFrame.wait==NativeGame.NATIVE_PANEL&&storyPointerActive();
    }
    private void nativePanelBack(){
        if(!nativePanelActive())return;
        if(currentFrame.nativePanelMode==51&&currentFrame.nativePanelSavable)runner.nativePanelMenu(currentFrame,13);
        else runner.nativePanelInput(currentFrame,lastX,lastY,false,true);
    }
    static int pointerCoordinate(float raw,int origin,int logicalSize,int viewportSize,boolean clip){
        double scaled=((double)raw-origin)*logicalSize/Math.max(1,viewportSize);
        int value=Double.isNaN(scaled)?-1:(int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,Math.floor(scaled)));
        return clip?Math.max(0,Math.min(logicalSize-1,value)):value;
    }
    private void storyPointer(MotionEvent event){
        stage.getLocationOnScreen(storyOrigin);
        boolean nativePanel=nativePanelActive();
        int x=pointerCoordinate(event.getRawX(),storyOrigin[0],width,stage.getWidth(),!nativePanel);
        int y=pointerCoordinate(event.getRawY(),storyOrigin[1],height,stage.getHeight(),!nativePanel);
        if(x!=lastX||y!=lastY){
            lastX=x;lastY=y;
            if(nativePanel)runner.nativePanelInput(currentFrame,x,y,false,false);
            else runner.pointer(x,y);
        }
    }
    @Override public void onBackPressed() {
        if(copyingGame){cancelGameCopy=true;return;}
        panelWarmup=null;
        if(movieErrorPanel!=null)return;
        if(moviePlayback!=null){moviePlayback.skip();return;}
        if(storyMovieExitActive)return;
        if(logoPanel!=null){logoPanel.skip();return;}
        if(startupOpening!=null){startupOpening.skip();return;}
        if(titlePanel!=null&&titlePanel.skipRestartExit())return;
        if(launchingGame&&runner!=null)return;
        if(sceneEntrance!=null){sceneEntrance.skip();return;}
        if(returningToTitle)return;
        if(preparingPanel!=null){
            if(preparingPanel instanceof GamePlacePanel||isModalPanel(preparingPanel))return;
            discardPanelPreparation();
            if(runner!=null&&panelsOpen==0)runner.menu(false);
            return;
        }
        if(gamePanel!=null){
            if(gamePanel instanceof GameSystemMenuPanel&&((GameSystemMenuPanel)gamePanel).preparingExtension())return;
            if(gamePanel.awaitingCapturedBackdrop()){closeOriginalPanel();return;}
            if(gamePanel.skipTransition())return;
            if(gamePanel.backOverlay())return;
            if(!(gamePanel instanceof GameBacklogPanel)&&
                !(gamePanel instanceof GameGalleryPanel&&((GameGalleryPanel)gamePanel).screen()==GalleryCatalog.Screen.VIEW))systemClick();
            if(panelBack!=null)panelBack.run();else closeOriginalPanel();
        }else if(nativePanelActive()){nativePanelBack();}
        else if(uiHidden){uiHidden=false;updateVisibility();}
        else if(runner==null){
            if(!fromTyn&&!showingLauncher){chooseHome(null);}
            else{finish();}
        }
        else if(titlePanel!=null)finish();
        else showMenu();
    }
    @Override protected void onResume() { super.onResume(); foreground=true; immersive();
        if(gamePanel!=null)gamePanel.uiActive(moviePlayback==null&&movieErrorPanel==null);
        statusActive(true);
        if(permissionPending) {
            permissionPending=false;
            if(runner==null&&!fromTyn){
                if(game!=null&&hasRequiredStandaloneStoragePermission()){
                    if(storageGateDialog!=null&&storageGateDialog.isShowing())storageGateDialog.dismiss();
                    storageGateDialog=null;
                    inspectAccess();
                }else{
                    enforceStandaloneStorageGate();
                }
            }else if(runner==null){
                inspectAccess();
            }
        } else if(runner==null&&!fromTyn&&showingLauncher){
            enforceStandaloneStorageGate();
        }
        if(runner!=null) runner.active(true);
        if(moviePlayback!=null)moviePlayback.resume();
        if(movieErrorPanel!=null)movieErrorPanel.uiActive(true);
    }
    @Override protected void onPause() {foreground=false;clearCtrlSkip();if(gamePanel!=null)gamePanel.uiActive(false);statusActive(false); if(runner!=null) runner.active(false);
        if(moviePlayback!=null)moviePlayback.pause();
        if(movieErrorPanel!=null)movieErrorPanel.uiActive(false);super.onPause();}
    @Override protected void onDestroy() {
        cancelGameCopy=true;
        if(storageGateDialog!=null&&storageGateDialog.isShowing()){storageGateDialog.dismiss();storageGateDialog=null;}
        if(launcherView!=null){launcherView.destroy();launcherView=null;}
        if(moviePlayback!=null){moviePlayback.dispose();moviePlayback=null;}
        if(movieErrorPanel!=null){movieErrorPanel.discardBackground();movieErrorPanel=null;}
        ui.removeCallbacksAndMessages(null);
        discardPanelPreparation();
        if(preferences!=null)preferences.unregisterOnSharedPreferenceChangeListener(settingsListener);
        if(userPreferences!=null)userPreferences.unregisterOnSharedPreferenceChangeListener(readingListener);
        for(AlertDialog dialog:new ArrayList<>(openedPanels))dialog.dismiss();
        storyPlane.release();
        if(runner!=null){runner.close();runner=null;}super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(int code,String[] perms,int[] grants) {
        super.onRequestPermissionsResult(code,perms,grants);
        if(code==21 && runner==null) {
            permissionPending=false;
            if(!fromTyn){
                if(game!=null&&hasRequiredStandaloneStoragePermission()){
                    if(storageGateDialog!=null&&storageGateDialog.isShowing())storageGateDialog.dismiss();
                    storageGateDialog=null;
                    inspectAccess();
                }else{
                    enforceStandaloneStorageGate();
                }
            }else{
                inspectAccess();
            }
        }
    }
}
