package com.google.ar.core.examples.java.helloar;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Config.InstantPlacementMode;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.ImageMetadata;
import com.google.ar.core.InstantPlacementPoint;
import com.google.ar.core.LightEstimate;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DepthSettings;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings;
import com.google.ar.core.examples.java.common.helpers.PointCloudHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TapHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.samplerender.Framebuffer;
import com.google.ar.core.examples.java.common.samplerender.GLError;
import com.google.ar.core.examples.java.common.samplerender.Mesh;
import com.google.ar.core.examples.java.common.samplerender.SampleRender;
import com.google.ar.core.examples.java.common.samplerender.Shader;
import com.google.ar.core.examples.java.common.samplerender.Texture;
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer;
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.CloudAnchorsNotConfiguredException;
import com.google.ar.core.exceptions.MetadataNotFoundException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import static com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES;
import static com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
public class ScanActivity extends AppCompatActivity implements SampleRender.Renderer {

    private static final String TAG = HelloArActivity.class.getSimpleName();

    private static final String SEARCHING_PLANE_MESSAGE = "Searching for surfaces...";
    private static final String WAITING_FOR_TAP_MESSAGE = "Tap on a surface to place an object.";

    // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
    // constants.
    private static final float[] sphericalHarmonicFactors = {
            0.282095f,
            -0.325735f,
            0.325735f,
            -0.325735f,
            0.273137f,
            -0.273137f,
            0.078848f,
            -0.273137f,
            0.136569f,
    };

    private static final float Z_NEAR = 0.1f;
    private static final float Z_FAR = 100f;

    private static final int CUBEMAP_RESOLUTION = 16;
    private static final int CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32;

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private BackgroundRenderer depthBackgroundRender;
    private GLSurfaceView depthSurfaceView;
    private SampleRender depthRender;
    private GLSurfaceView surfaceView;
    private TextView degView;

    private boolean installRequested;

    private Session session;
    private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
    private DisplayRotationHelper displayRotationHelper;
    private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
    private TapHelper tapHelper;
    private SampleRender render;

    private PlaneRenderer planeRenderer;
    private BackgroundRenderer backgroundRenderer;
    private Framebuffer virtualSceneFramebuffer;
    private boolean hasSetTextureNames = false;

    private final DepthSettings depthSettings = new DepthSettings();
    private boolean[] depthSettingsMenuDialogCheckboxes = new boolean[2];

    private final InstantPlacementSettings instantPlacementSettings = new InstantPlacementSettings();
    private boolean[] instantPlacementSettingsMenuDialogCheckboxes = new boolean[1];
    // Assumed distance from the device camera to the surface on which user will try to place objects.
    // This value affects the apparent scale of objects while the tracking method of the
    // Instant Placement point is SCREENSPACE_WITH_APPROXIMATE_DISTANCE.
    // Values in the [0.2, 2.0] meter range are a good choice for most AR experiences. Use lower
    // values for AR experiences where users are expected to place objects on surfaces close to the
    // camera. Use larger values for experiences where the user will likely be standing and trying to
    // place an object on the ground or floor in front of them.
    private static final float APPROXIMATE_DISTANCE_METERS = 2.0f;

    // Point Cloud
    private VertexBuffer pointCloudVertexBuffer;
    private Mesh pointCloudMesh;
    private Shader pointCloudShader;

    // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
    // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
    private long lastPointCloudTimestamp = 0;
    private long lastCameraImageTimestamp=0;

    // Virtual object (ARCore pawn)
    private Mesh virtualObjectMesh;
    private Shader virtualObjectShader;



    private final ArrayList<Anchor> anchors = new ArrayList<>();
    public ArrayList<com.google.ar.core.examples.java.common.helpers.Point>pc;
    // Environmental HDR
    private Texture dfgTexture;
    private SpecularCubemapFilter cubemapFilter;

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private final float[] modelMatrix = new float[16];
    private final float[] viewMatrix = new float[16];
    private final float[] projectionMatrix = new float[16];
    private final float[] modelViewMatrix = new float[16]; // view x model
    private final float[] modelViewProjectionMatrix = new float[16]; // projection x view x model
    private final float[] sphericalHarmonicsCoefficients = new float[9 * 3];
    private final float[] viewInverseMatrix = new float[16];
    private final float[] worldLightDirection = {0.0f, 0.0f, 0.0f, 0.0f};
    private final float[] viewLightDirection = new float[4]; // view x world light direction
    private boolean firstDraw=true;
    private int viewWidth;
    private int viewHeight;
    private float deg=0;

    //INIT cloud anchor state
    private enum AppAnchorState {
        NONE,
        HOSTING,
        HOSTED
    }
    private AppAnchorState appAnchorState = AppAnchorState.NONE;

    //region Implement View Event
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //add
        try {
            createSession();
        } catch (UnavailableSdkTooOldException e) {
            e.printStackTrace();
        } catch (UnavailableDeviceNotCompatibleException e) {
            e.printStackTrace();
        } catch (UnavailableArcoreNotInstalledException e) {
            e.printStackTrace();
        } catch (UnavailableApkTooOldException e) {
            e.printStackTrace();
        }
        //


        setContentView(R.layout.scan);
        surfaceView = findViewById(R.id.surfacev);
        depthSurfaceView=findViewById(R.id.surfacev);
        degView=findViewById(R.id.textdeg);
        displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);
        pc= new ArrayList<>();
        Button twoDbtn=(Button)findViewById(R.id.twodee);
        twoDbtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                passTwoD();
            }
        });
        // Set up touch listener.
        tapHelper = new TapHelper(/*context=*/ this);
        surfaceView.setOnTouchListener(tapHelper);

        // Set up renderer.
        render = new SampleRender(surfaceView, this, getAssets());


        installRequested = false;

        depthSettings.onCreate(this);
        instantPlacementSettings.onCreate(this);
        ImageButton settingsButton = findViewById(R.id.settings_button2);
        settingsButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PopupMenu popup = new PopupMenu(ScanActivity.this, v);
                        popup.setOnMenuItemClickListener(ScanActivity.this::settingsMenuClick);
                        popup.inflate(R.menu.settings_menu);
                        popup.show();
                    }
                });

    }

    //add
    public void createSession() throws UnavailableSdkTooOldException, UnavailableDeviceNotCompatibleException, UnavailableArcoreNotInstalledException, UnavailableApkTooOldException {
        // Create a new ARCore session.
        session = new Session(this);

        // Create a session config.
        Config config = new Config(session);

        // Do feature-specific operations here, such as enabling depth or turning on
        // support for Augmented Faces.

        // Configure the session.
        session.configure(config);
    }
    //

    private void passTwoD(){

            PointCloudSaving.pointC=pc;
            Intent intent=new Intent(this,PointCloudDrawing.class);
            //intent.putExtra("data",pc);
            startActivity(intent);
    }

    /** Menu button to launch feature specific settings. */
    protected boolean settingsMenuClick(MenuItem item) {
        if (item.getItemId() == R.id.depth_settings) {
            launchDepthSettingsMenuDialog();
            return true;
        } else if (item.getItemId() == R.id.instant_placement_settings) {
            launchInstantPlacementSettingsMenuDialog();
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }

        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (session == null) {
            Exception exception = null;
            String message = null;
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this);
                    return;
                }

                // Create the session.
                session = new Session(/* context= */ this);
            } catch (UnavailableArcoreNotInstalledException
                    | UnavailableUserDeclinedInstallationException e) {
                message = "Please install ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Please update ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Please update this app";
                exception = e;
            } catch (UnavailableDeviceNotCompatibleException e) {
                message = "This device does not support AR";
                exception = e;
            } catch (Exception e) {
                message = "Failed to create AR session";
                exception = e;
            }

            if (message != null) {
                messageSnackbarHelper.showError(this, message);
                Log.e(TAG, "Exception creating session", exception);
                return;
            }
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            configureSession();

            session.resume();
        } catch (CameraNotAvailableException e) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
            session = null;
            return;
        }
        depthSurfaceView.onResume();
        surfaceView.onResume();
        displayRotationHelper.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (session != null) {

            displayRotationHelper.onPause();
            surfaceView.onPause();
            depthSurfaceView.onPause();
            session.pause();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            // Use toast instead of snackbar here since the activity will exit.
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                    .show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
    }
    //endregion

    //region GLSurface Event Implement
    @Override
    public void onSurfaceCreated(SampleRender render) {
        // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
        // an IOException.
        try {
            planeRenderer = new PlaneRenderer(render);
            backgroundRenderer = new BackgroundRenderer(render);
            virtualSceneFramebuffer = new Framebuffer(render, /*width=*/ 1, /*height=*/ 1);

            cubemapFilter =
                    new SpecularCubemapFilter(
                            render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES);
            // Load DFG lookup table for environmental lighting
            dfgTexture =
                    new Texture(
                            render,
                            Texture.Target.TEXTURE_2D,
                            Texture.WrapMode.CLAMP_TO_EDGE,
                            /*useMipmaps=*/ false);
            // The dfg.raw file is a raw half-float texture with two channels.
            final int dfgResolution = 64;
            final int dfgChannels = 2;
            final int halfFloatSize = 2;

            ByteBuffer buffer =
                    ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize);
            try (InputStream is = getAssets().open("models/dfg.raw")) {
                is.read(buffer.array());
            }
            // SampleRender abstraction leaks here.
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.getTextureId());
            GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture");
            GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D,
                    /*level=*/ 0,
                    GLES30.GL_RG16F,
                    /*width=*/ dfgResolution,
                    /*height=*/ dfgResolution,
                    /*border=*/ 0,
                    GLES30.GL_RG,
                    GLES30.GL_HALF_FLOAT,
                    buffer);
            GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D");

            // Point cloud
            pointCloudShader =
                    Shader.createFromAssets(
                            render, "shaders/point_cloud.vert", "shaders/point_cloud.frag", /*defines=*/ null)
                            .setVec4(
                                    "u_Color", new float[] {31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f})
                            .setFloat("u_PointSize", 5.0f);
            // four entries per vertex: X, Y, Z, confidence
            pointCloudVertexBuffer =
                    new VertexBuffer(render, /*numberOfEntriesPerVertex=*/ 4, /*entries=*/ null);
            final VertexBuffer[] pointCloudVertexBuffers = {pointCloudVertexBuffer};
            pointCloudMesh =
                    new Mesh(
                            render, Mesh.PrimitiveMode.POINTS, /*indexBuffer=*/ null, pointCloudVertexBuffers);

            // Virtual object to render (ARCore pawn)
            Texture virtualObjectAlbedoTexture =
                    Texture.createFromAsset(
                            render,
                            "models/pawn_albedo.png",
                            Texture.WrapMode.CLAMP_TO_EDGE,
                            Texture.ColorFormat.SRGB);
            Texture virtualObjectPbrTexture =
                    Texture.createFromAsset(
                            render,
                            "models/pawn_roughness_metallic_ao.png",
                            Texture.WrapMode.CLAMP_TO_EDGE,
                            Texture.ColorFormat.LINEAR);
            virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj");
            virtualObjectShader =
                    Shader.createFromAssets(
                            render,
                            "shaders/environmental_hdr.vert",
                            "shaders/environmental_hdr.frag",
                            /*defines=*/ new HashMap<String, String>() {
                                {
                                    put(
                                            "NUMBER_OF_MIPMAP_LEVELS",
                                            Integer.toString(cubemapFilter.getNumberOfMipmapLevels()));
                                }
                            })
                            .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
                            .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
                            .setTexture("u_Cubemap", cubemapFilter.getFilteredCubemapTexture())
                            .setTexture("u_DfgTexture", dfgTexture);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read a required asset file", e);
            messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
        }
    }

    @Override
    public void onSurfaceChanged(SampleRender render, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        virtualSceneFramebuffer.resize(width, height);
        viewWidth=width;
        viewHeight=height;
        //deg=displayRotationHelper.GetDisplayRotation();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String msg=String.format("widrh:%d\r\nheight:%d\r\ndeg:%f",viewWidth,viewHeight,deg);
                degView.setText(msg);
            }
        });

    }

    @Override
    public void onDrawFrame(SampleRender render) {
        if (session == null) {
            return;
        }

        // Texture names should only be set once on a GL thread unless they change. This is done during
        // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
        // initialized during the execution of onSurfaceCreated.
        if (!hasSetTextureNames) {
            session.setCameraTextureNames(
                    new int[] {backgroundRenderer.getCameraColorTexture().getTextureId()});
            hasSetTextureNames = true;
        }

        // -- Update per-frame state

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session);



        // Obtain the current frame from ARSession. When the configuration is set to
        // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
        // camera framerate.
        Frame frame;
        try {
            frame = session.update();
        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera not available during onDrawFrame", e);
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
            return;
        }
        Camera camera = frame.getCamera();

        // Update BackgroundRenderer state to match the depth settings.
        try {
            backgroundRenderer.setUseDepthVisualization(
                    render, depthSettings.depthColorVisualizationEnabled());
            backgroundRenderer.setUseOcclusion(render, depthSettings.useDepthForOcclusion());
        } catch (IOException e) {
            Log.e(TAG, "Failed to read a required asset file", e);
            messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
            return;
        }
        // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
        // used to draw the background camera image.
        backgroundRenderer.updateDisplayGeometry(frame);

        if (camera.getTrackingState() == TrackingState.TRACKING
                && (depthSettings.useDepthForOcclusion()
                || depthSettings.depthColorVisualizationEnabled())) {
            try (Image depthImage = frame.acquireDepthImage()) {
                backgroundRenderer.updateCameraDepthTexture(depthImage);
            } catch (NotYetAvailableException e) {
                // This normally means that depth data is not available yet. This is normal so we will not
                // spam the logcat with this.
            }
        }

        Collection<Plane> planes = session.getAllTrackables(Plane.class);
        FloatBuffer pointCloudValue=null;
        // Get projection matrix.
        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);

        // Get camera matrix and draw.
        camera.getViewMatrix(viewMatrix, 0);

        float[] viewProjectMatrix=new float[16];
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0);


        backgroundRenderer.drawBackground(render);

        // Handle one tap per frame.
        handleTap(frame, camera);



        // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
        trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

        // Show a message based on whether tracking has failed, if planes are detected, and if the user
        // has placed any objects.
        String message = null;
        if (camera.getTrackingState() == TrackingState.PAUSED) {
            if (camera.getTrackingFailureReason() == TrackingFailureReason.NONE) {
                message = SEARCHING_PLANE_MESSAGE;
            } else {
                message = TrackingStateHelper.getTrackingFailureReasonString(camera);
            }
        } else if (hasTrackingPlane()) {
            if (anchors.isEmpty()) {
                message = WAITING_FOR_TAP_MESSAGE;
            }
        } else {
            message = SEARCHING_PLANE_MESSAGE;
        }
        if (message == null) {
            messageSnackbarHelper.hide(this);
        } else {
            messageSnackbarHelper.showMessage(this, message);
        }

        // -- Draw background

        if (frame.getTimestamp() != 0) {
            // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
            // drawing possible leftover data from previous sessions if the texture is reused.
            backgroundRenderer.drawBackground(render);
        }

        // If not tracking, don't draw 3D objects.
        if (camera.getTrackingState() == TrackingState.PAUSED){
            return;
        }


        // Visualize tracked points.
        // Use try-with-resources to automatically release the point cloud.
        if(pointCloudValue==null) {
            try (PointCloud pointCloud = frame.acquirePointCloud()) {
                if (pointCloud.getTimestamp() > lastPointCloudTimestamp) {
                    pointCloudVertexBuffer.set(pointCloud.getPoints());
                    lastPointCloudTimestamp = pointCloud.getTimestamp();
                }
                pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);
                render.draw(pointCloudMesh, pointCloudShader);
            }
        }else{
            pointCloudVertexBuffer.set(pointCloudValue);
            pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);
            render.draw(pointCloudMesh, pointCloudShader);
        }



        // Visualize planes.
        planeRenderer.drawPlanes(
                render,
                planes,
                camera.getDisplayOrientedPose(),
                projectionMatrix);

        // -- Draw occluded virtual objects

        // Update lighting parameters in the shader
        updateLightEstimation(frame.getLightEstimate(), viewMatrix);

        // Visualize anchors created by touch.
        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f);
        for (Anchor anchor : anchors) {
            TrackingState anchorTracking=anchor.getTrackingState();


            // Get the current pose of an Anchor in world space. The Anchor pose is updated
            // during calls to session.update() as ARCore refines its estimate of the world.
            anchor.getPose().toMatrix(modelMatrix, 0);

            // Calculate model/view/projection matrices
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0);
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);

            // Update shader properties and draw
            virtualObjectShader.setMat4("u_ModelView", modelViewMatrix);
            virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);
            render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer);
        }

        // Compose the virtual scene with the background.
        backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR);
    }

    // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
    private void handleTap(Frame frame, Camera camera) {
        //找到全部的TAP
        MotionEvent tap = tapHelper.poll();
        //辨識出TAP放入list內
        if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
            List<HitResult> hitResultList;
            //instant placement 開啟/關閉
            if (instantPlacementSettings.isInstantPlacementEnabled()) {
                hitResultList =
                        frame.hitTestInstantPlacement(tap.getX(), tap.getY(), APPROXIMATE_DISTANCE_METERS);
            } else {
                hitResultList = frame.hitTest(tap);
            }

            for (HitResult hit : hitResultList) {
                // If any plane, Oriented Point, or Instant Placement Point was hit, create an anchor.
                Trackable trackable = hit.getTrackable();
                // If a plane was hit, check that it was hit inside the plane polygon.
                if ((trackable instanceof Plane
                        && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                        && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camera.getPose()) > 0))
                        || (trackable instanceof Point
                        && ((Point) trackable).getOrientationMode()
                        == OrientationMode.ESTIMATED_SURFACE_NORMAL)
                        || (trackable instanceof InstantPlacementPoint)) {
                    // Cap the number of objects created. This avoids overloading both the
                    // rendering system and ARCore.
                    if (anchors.size() >= 40) {
                        anchors.get(0).detach();
                        anchors.remove(0);
                    }
                    float[] viewProjectMatrix=new float[16];
                    Matrix.multiplyMM(viewProjectMatrix,0,projectionMatrix,0,viewMatrix,0);
                    //對照
                    Pose hitPos = hit.getHitPose();
                    //找出點擊在螢幕的XY位置
                    float x=tap.getX();
                    float y=tap.getY();
                    float depthXScale=1;
                    float depthYScale=1;
                    //??
                    float xStep=1f / viewWidth;
                    float yStep=1f / viewHeight;
                    //設定暫存區
                    int bufferSize=viewHeight*viewWidth*4;
                    ByteBuffer colorBuffer=ByteBuffer.allocateDirect(bufferSize);
                    ByteBuffer depthBuffer=ByteBuffer.allocateDirect(bufferSize/2);
                    //GLE

                    GLES30.glReadPixels(0,0,viewWidth,viewHeight,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,colorBuffer);


                    //基本資料型別設定
                    int byteIndex=0;
                    int bytePerPixel=2;
                    int rowStride=160;
                    int depthWidth=160;
                    int depthHeight=120;
                    //
                    try(Image depthImage=frame.acquireDepthImage()){
                        depthWidth=depthImage.getWidth();
                        depthHeight=depthImage.getHeight();
                        depthXScale=(float)depthWidth / (float) Math.max(viewWidth,viewHeight);
                        depthYScale=(float)depthHeight / (float)Math.min(viewWidth, viewHeight);
                        Image.Plane plane=depthImage.getPlanes()[0];
                        bytePerPixel=plane.getPixelStride();
                        rowStride=plane.getRowStride();
                        depthBuffer=plane.getBuffer();
                    } catch (NotYetAvailableException e) {
//            e.printStackTrace();
                    }
                    //翻轉=>XY互換且縮放
                    int xDepth=(int)(y*depthXScale);
                    int yDepth=(int)((viewWidth-x)*depthYScale);


                    int index=(int)((viewHeight-y)*viewWidth+x)*4;
                    int b = colorBuffer.get(index) & 0xff;
                    int g= colorBuffer.get(index+1) & 0xff;
                    int r= colorBuffer.get(index+2) & 0xff;
                    int a= colorBuffer.get(index+3)  & 0xff;
                    int color=0xff000000 +(r << 16) + (g << 8)+b;
                    byteIndex=(int)(xDepth*bytePerPixel+yDepth*rowStride);
                    int depth1 = depthBuffer.get(byteIndex) & 0xff;
                    int depth2 = depthBuffer.get(byteIndex+1) & 0xff;
                    short depth=(short) (depth1+(depth2 << 8));


                    float[] cloudPoint=new float[4];


                    float[] xyzw=PointCloudHelper.screenPointToRay(x,y,depth,viewWidth,viewHeight,viewProjectMatrix);
                    float[] xyz={
                            xyzw[0],
                            xyzw[1],
                            xyzw[2]
                    };
                    float[] ori={0,0,0,1f};
                    Pose pose=new Pose(xyz,ori);

                    Anchor anchor = session.createAnchor(pose);
                    try{
                    anchor = session.hostCloudAnchor(anchor);
                    String cloudAnchorID = anchor.getCloudAnchorId();
                    Log.d("Cloud Anchor id: ",anchor.getCloudAnchorId());
                    appAnchorState = AppAnchorState.HOSTING;

                    Log.d("Cloud Anchor state: ",anchor.getCloudAnchorState()+"");}
                    catch(CloudAnchorsNotConfiguredException e){
                        Log.d("anchor exception"," failed");

                    }







                    float finalDepthYScale = depthYScale;
                    float finalDepthXScale = depthXScale;
                    int finalDepthWidth = depthWidth;
                    int finalDepthHeight = depthHeight;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String msg=String.format("widrh:%d height:%d deg:%f\r\nTapX:%f\tTapY:%f\r\nPose:%f %f %f\r\nx:%f\ty:%f\tz:%f\r\na:%d r:%d g:%d b:%d c:%d\r\ndepth:%d dx:%d dy:%d\r\ndepthHidth:%d depthHeight:%d\r\nxScale:%f yScale:%f",
                                    viewWidth,viewHeight,deg,
                                    x,y,
                                    hitPos.tx(),hitPos.ty(), hitPos.tz(),
                                    xyz[0],xyz[1],xyz[2],
                                    a,r,g,b,color,
                                    depth, xDepth, yDepth,
                                    finalDepthWidth, finalDepthHeight,
                                    finalDepthXScale, finalDepthYScale);
                            degView.setText(msg);
                            degView.setTextColor(color);
//              degView.setBackgroundColor((Integer.reverse(color)&0xFFFFFF)+0xee000000);
                            degView.setBackgroundColor(0xeeffffff);
                            com.google.ar.core.examples.java.common.helpers.Point nupoint=new com.google.ar.core.examples.java.common.helpers.Point(xyz[0],xyz[1],xyz[2]);
                            pc.add(nupoint);
                        }
                    });
                    // Adding an Anchor tells ARCore that it should track this position in
                    // space. This anchor is created on the Plane to place the 3D model
                    // in the correct position relative both to the world and to the plane.

                    anchors.add(anchor);

                    // For devices that support the Depth API, shows a dialog to suggest enabling
                    // depth-based occlusion. This dialog needs to be spawned on the UI thread.
                    this.runOnUiThread(this::showOcclusionDialogIfNeeded);

                    // Hits are sorted by depth. Consider only closest hit on a plane, Oriented Point, or
                    // Instant Placement Point.
                    break;
                }
            }
        }
    }

    /**
     * Shows a pop-up dialog on the first call, determining whether the user wants to enable
     * depth-based occlusion. The result of this dialog can be retrieved with useDepthForOcclusion().
     */
    private void showOcclusionDialogIfNeeded() {
        boolean isDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
        if (!depthSettings.shouldShowDepthEnableDialog() || !isDepthSupported) {
            return; // Don't need to show dialog.
        }

        // Asks the user whether they want to use depth-based occlusion.
        new AlertDialog.Builder(this)
                .setTitle(R.string.options_title_with_depth)
                .setMessage(R.string.depth_use_explanation)
                .setPositiveButton(
                        R.string.button_text_enable_depth,
                        (DialogInterface dialog, int which) -> {
                            depthSettings.setUseDepthForOcclusion(true);
                        })
                .setNegativeButton(
                        R.string.button_text_disable_depth,
                        (DialogInterface dialog, int which) -> {
                            depthSettings.setUseDepthForOcclusion(false);
                        })
                .show();
    }
    //endregion

    //region menu
    private void launchInstantPlacementSettingsMenuDialog() {
        resetSettingsMenuDialogCheckboxes();
        Resources resources = getResources();
        new AlertDialog.Builder(this)
                .setTitle(R.string.options_title_instant_placement)
                .setMultiChoiceItems(
                        resources.getStringArray(R.array.instant_placement_options_array),
                        instantPlacementSettingsMenuDialogCheckboxes,
                        (DialogInterface dialog, int which, boolean isChecked) ->
                                instantPlacementSettingsMenuDialogCheckboxes[which] = isChecked)
                .setPositiveButton(
                        R.string.done,
                        (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
                .setNegativeButton(
                        android.R.string.cancel,
                        (DialogInterface dialog, int which) -> resetSettingsMenuDialogCheckboxes())
                .show();
    }

    /** Shows checkboxes to the user to facilitate toggling of depth-based effects. */
    private void launchDepthSettingsMenuDialog() {
        // Retrieves the current settings to show in the checkboxes.
        resetSettingsMenuDialogCheckboxes();

        // Shows the dialog to the user.
        Resources resources = getResources();
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            // With depth support, the user can select visualization options.
            new AlertDialog.Builder(this)
                    .setTitle(R.string.options_title_with_depth)
                    .setMultiChoiceItems(
                            resources.getStringArray(R.array.depth_options_array),
                            depthSettingsMenuDialogCheckboxes,
                            (DialogInterface dialog, int which, boolean isChecked) ->
                                    depthSettingsMenuDialogCheckboxes[which] = isChecked)
                    .setPositiveButton(
                            R.string.done,
                            (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
                    .setNegativeButton(
                            android.R.string.cancel,
                            (DialogInterface dialog, int which) -> resetSettingsMenuDialogCheckboxes())
                    .show();
        } else {
            // Without depth support, no settings are available.
            new AlertDialog.Builder(this)
                    .setTitle(R.string.options_title_without_depth)
                    .setPositiveButton(
                            R.string.done,
                            (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
                    .show();
        }
    }

    private void applySettingsMenuDialogCheckboxes() {
        depthSettings.setUseDepthForOcclusion(depthSettingsMenuDialogCheckboxes[0]);
        depthSettings.setDepthColorVisualizationEnabled(depthSettingsMenuDialogCheckboxes[1]);
        instantPlacementSettings.setInstantPlacementEnabled(
                instantPlacementSettingsMenuDialogCheckboxes[0]);
        configureSession();
    }

    private void resetSettingsMenuDialogCheckboxes() {
        depthSettingsMenuDialogCheckboxes[0] = depthSettings.useDepthForOcclusion();
        depthSettingsMenuDialogCheckboxes[1] = depthSettings.depthColorVisualizationEnabled();
        instantPlacementSettingsMenuDialogCheckboxes[0] =
                instantPlacementSettings.isInstantPlacementEnabled();
    }
    //endregion

    /** Checks if we detected at least one plane. */
    private boolean hasTrackingPlane() {
        for (Plane plane : session.getAllTrackables(Plane.class)) {
            if (plane.getTrackingState() == TrackingState.TRACKING) {
                return true;
            }
        }
        return false;
    }

    /** Update state based on the current frame's light estimation. */
    private void updateLightEstimation(LightEstimate lightEstimate, float[] viewMatrix) {
        if (lightEstimate.getState() != LightEstimate.State.VALID) {
            virtualObjectShader.setBool("u_LightEstimateIsValid", false);
            return;
        }
        virtualObjectShader.setBool("u_LightEstimateIsValid", true);

        Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0);
        virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix);

        updateMainLight(
                lightEstimate.getEnvironmentalHdrMainLightDirection(),
                lightEstimate.getEnvironmentalHdrMainLightIntensity(),
                viewMatrix);
        updateSphericalHarmonicsCoefficients(
                lightEstimate.getEnvironmentalHdrAmbientSphericalHarmonics());
        cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap());
    }

    private void updateMainLight(float[] direction, float[] intensity, float[] viewMatrix) {
        // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
        worldLightDirection[0] = direction[0];
        worldLightDirection[1] = direction[1];
        worldLightDirection[2] = direction[2];
        Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0);
        virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection);
        virtualObjectShader.setVec3("u_LightIntensity", intensity);
    }

    private void updateSphericalHarmonicsCoefficients(float[] coefficients) {

        if (coefficients.length != 9 * 3) {
            throw new IllegalArgumentException(
                    "The given coefficients array must be of length 27 (3 components per 9 coefficients");
        }

        // Apply each factor to every component of each coefficient
        for (int i = 0; i < 9 * 3; ++i) {
            sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3];
        }
        virtualObjectShader.setVec3Array(
                "u_SphericalHarmonicsCoefficients", sphericalHarmonicsCoefficients);
    }

    /** Configures the session with feature settings. */
    private void configureSession() {
        Config config = session.getConfig();
        //add
        config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);
        config.setFocusMode(Config.FocusMode.AUTO);
        //
        config.setLightEstimationMode(Config.LightEstimationMode.ENVIRONMENTAL_HDR);
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.setDepthMode(Config.DepthMode.AUTOMATIC);
        } else {
            config.setDepthMode(Config.DepthMode.DISABLED);
        }
        if (instantPlacementSettings.isInstantPlacementEnabled()) {
            config.setInstantPlacementMode(InstantPlacementMode.LOCAL_Y_UP);
        } else {
            config.setInstantPlacementMode(InstantPlacementMode.DISABLED);
        }
        session.configure(config);
    }

    protected Config getSessionConfiguration(Session session) {

        Config config = new Config(session);
        //getPlaneDiscoveryController().setInstructionView(null);
        config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);
        config.setFocusMode(Config.FocusMode.AUTO);
        session.configure(config);


        return config;
    }

    private void toWorldCoordinate(float xcor, float ycor, Frame frame){
        float x = xcor;
        float y = ycor;
        int colSum = viewHeight;
        int rowSum = viewWidth;
        ByteBuffer originXBuffer = ByteBuffer.allocateDirect(colSum * rowSum);
        ByteBuffer originYBuffer = ByteBuffer.allocateDirect(colSum * rowSum);
        ByteBuffer worldXBuffer = ByteBuffer.allocateDirect(colSum * rowSum);
        ByteBuffer worldYBuffer = ByteBuffer.allocateDirect(colSum * rowSum);
        ByteBuffer worldZBuffer = ByteBuffer.allocateDirect(colSum * rowSum);
        ByteBuffer DepthBuffer = ByteBuffer.allocateDirect(colSum * rowSum);
        ByteBuffer ColorBuffer = ByteBuffer.allocateDirect(colSum * rowSum);
        ByteBuffer colorABuffer = ByteBuffer.allocateDirect(colSum * rowSum);
        ByteBuffer colorRBuffer = ByteBuffer.allocateDirect(colSum * rowSum);
        ByteBuffer colorGBuffer = ByteBuffer.allocateDirect(colSum * rowSum);
        ByteBuffer colorBBuffer = ByteBuffer.allocateDirect(colSum * rowSum);
        ByteBuffer degBuffer = ByteBuffer.allocateDirect(colSum * rowSum);
        float depthXScale = 1;
        float depthYScale = 1;
        int bufferSize = viewHeight * viewWidth * 4;
        ByteBuffer colorBuffer = ByteBuffer.allocateDirect(bufferSize);
        ByteBuffer depthBuffer = ByteBuffer.allocateDirect(bufferSize / 2);
        //用depth方法實驗
        int byteIndex = 0;
        int bytePerPixel = 2;
        int rowStride = 160;
        int depthWidth = 160;
        int depthHeight = 120;

        try (Image depthImage = frame.acquireDepthImage()) {
            depthWidth = depthImage.getWidth();
            depthHeight = depthImage.getHeight();
            depthXScale = (float) depthWidth / (float) Math.max(viewWidth, viewHeight);
            depthYScale = (float) depthHeight / (float) Math.min(viewWidth, viewHeight);
            Image.Plane plane = depthImage.getPlanes()[0];
            bytePerPixel = plane.getPixelStride();
            rowStride = plane.getRowStride();
            depthBuffer = plane.getBuffer();
        } catch (NotYetAvailableException e) {
        }

        int xDepth = (int) (y * depthXScale);
        int yDepth = (int) ((viewWidth - x) * depthYScale);


        int index = (int) ((viewHeight - y) * viewWidth + x) * 4;
        int b = colorBuffer.get(index) & 0xff;
        int g = colorBuffer.get(index + 1) & 0xff;
        int r = colorBuffer.get(index + 2) & 0xff;
        int a = colorBuffer.get(index + 3) & 0xff;

        int color = 0xff000000 + (r << 16) + (g << 8) + b;

        byteIndex = (int) (xDepth * bytePerPixel + yDepth * rowStride);
        int depth1 = depthBuffer.get(byteIndex) & 0xff;
        int depth2 = depthBuffer.get(byteIndex + 1) & 0xff;
        short depth = (short) (depth1 + (depth2 << 8));


        float[] cloudPoint = new float[4];

        float[] viewProjectMatrix = new float[16];
        Matrix.multiplyMM(viewProjectMatrix, 0, projectionMatrix, 0, viewMatrix, 0);

        float[] xyzw = PointCloudHelper.screenPointToRay(x, y, depth, viewWidth, viewHeight, viewProjectMatrix);
        float[] xyz = {
                xyzw[0],
                xyzw[1],
                xyzw[2]
        };
        float[] ori = {0, 0, 0, 1f};
        Pose pose = new Pose(xyz, ori);

        Anchor anchor = session.createAnchor(pose);
        float finalDepthYScale = depthYScale;
        float finalDepthXScale = depthXScale;
        int finalDepthWidth = depthWidth;
        int finalDepthHeight = depthHeight;
//                worldXBuffer.putFloat(xyz[0]);
//                worldYBuffer.putFloat(xyz[1]);
//                worldZBuffer.putFloat(xyz[2]);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String msg = String.format("width:%d height:%d deg:%f\r\noriginX:%f\toriginY:%f\r\nworldX:%f\tworldY:%f\tworldZ:%f\r\na:%d r:%d g:%d b:%d c:%d\r\ndepth:%d dx:%d dy:%d\r\n\r\n",
                        viewWidth, viewHeight, deg,
                        x, y,
                        xyz[0], xyz[1], xyz[2],
                        a, r, g, b, color,
                        depth, xDepth, yDepth);
                degView.append(msg);
            }
        });
        
    }

    private void autoScan(Frame frame, Camera camera) throws NotYetAvailableException, InterruptedException {
        int colSum = viewHeight;
        int rowSum = viewWidth;

        for (int x=0; x<rowSum; x+=100) {
            for (int y = 0; y < rowSum; y += 100) {
//                Thread.sleep(5*1000);
                try {
                    toWorldCoordinate(x, y, frame);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
