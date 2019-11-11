/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.core;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.location.Location;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.view.Display;
import android.view.Surface;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.annotation.UiThread;
import androidx.camera.core.CameraCaptureMetaData.AeState;
import androidx.camera.core.CameraCaptureMetaData.AfMode;
import androidx.camera.core.CameraCaptureMetaData.AfState;
import androidx.camera.core.CameraCaptureMetaData.AwbState;
import androidx.camera.core.CameraCaptureResult.EmptyCameraCaptureResult;
import androidx.camera.core.ForwardingImageProxy.OnImageCloseListener;
import androidx.camera.core.ImageOutputConfig.RotationValue;
import androidx.camera.core.impl.utils.Threads;
import androidx.camera.core.impl.utils.executor.CameraXExecutors;
import androidx.camera.core.impl.utils.futures.FutureCallback;
import androidx.camera.core.impl.utils.futures.FutureChain;
import androidx.camera.core.impl.utils.futures.Futures;
import androidx.concurrent.futures.CallbackToFutureAdapter;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A use case for taking a picture.
 *
 * <p>This class is designed for basic picture taking. It provides takePicture() functions to take
 * a picture to memory or save to a file, and provides image metadata.  Pictures are taken in
 * automatic mode after focus has converged. The flash mode can additionally be set by the
 * application.
 *
 * <p>TakePicture returns immediately and a listener is called to provide the results after the
 * capture completes. Multiple calls to takePicture will take pictures sequentially starting
 * after the previous picture is captured.
 *
 * <p>Note that focus and exposure metering regions can be controlled via {@link Preview}.
 *
 * <p>When capturing to memory, the captured image is made available through an {@link ImageProxy}
 * via an {@link ImageCapture.OnImageCapturedCallback}.
 */
@SuppressWarnings("ClassCanBeStatic") // TODO(b/141958189): Suppressed during upgrade to AGP 3.6.
public class ImageCapture extends UseCase {
    /**
     * Provides a static configuration with implementation-agnostic options.
     *
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    public static final Defaults DEFAULT_CONFIG = new Defaults();
    private static final String TAG = "ImageCapture";
    private static final long CHECK_3A_TIMEOUT_IN_MS = 1000L;
    private static final int MAX_IMAGES = 2;
    // Empty metadata object used as a placeholder for no user-supplied metadata.
    // Should be initialized to all default values.
    private static final Metadata EMPTY_METADATA = new Metadata();
    @Nullable
    private HandlerThread mProcessingImageResultThread;
    @Nullable
    private Handler mProcessingImageResultHandler;

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    final Deque<ImageCaptureRequest> mImageCaptureRequests = new ConcurrentLinkedDeque<>();
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
            SessionConfig.Builder mSessionConfigBuilder;
    private final CaptureConfig mCaptureConfig;
    private final ExecutorService mExecutor =
            Executors.newFixedThreadPool(
                    1,
                    new ThreadFactory() {
                        private final AtomicInteger mId = new AtomicInteger(0);

                        @Override
                        public Thread newThread(@NonNull Runnable r) {
                            return new Thread(
                                    r,
                                    CameraXThreads.TAG + "image_capture_" + mId.getAndIncrement());
                        }
                    });
    @NonNull
    final Executor mIoExecutor;
    private final CaptureCallbackChecker mSessionCallbackChecker = new CaptureCallbackChecker();
    private final CaptureMode mCaptureMode;

    /** The set of requests that will be sent to the camera for the final captured image. */
    private final CaptureBundle mCaptureBundle;
    private final int mMaxCaptureStages;

    /**
     * Processing that gets done to the mCaptureBundle to produce the final image that is produced
     * by {@link #takePicture(Executor, OnImageCapturedCallback)}
     */
    private final CaptureProcessor mCaptureProcessor;
    private final ImageCaptureConfig.Builder mUseCaseConfigBuilder;
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
            ImageReaderProxy mImageReader;
    /** Callback used to match the {@link ImageProxy} with the {@link ImageInfo}. */
    private CameraCaptureCallback mMetadataMatchingCaptureCallback;
    private ImageCaptureConfig mConfig;
    private DeferrableSurface mDeferrableSurface;

    /**
     * A flag to check 3A converged or not.
     *
     * <p>In order to speed up the taking picture process, trigger AF / AE should be skipped when
     * the flag is disabled. Set it to be enabled in the maximum quality mode and disabled in the
     * minimum latency mode.
     */
    private boolean mEnableCheck3AConverged;
    /** Current flash mode. */
    private FlashMode mFlashMode;

    /**
     * Creates a new image capture use case from the given configuration.
     *
     * @param userConfig for this use case instance
     * @throws IllegalArgumentException if the configuration is invalid.
     */
    public ImageCapture(@NonNull ImageCaptureConfig userConfig) {
        super(userConfig);
        mUseCaseConfigBuilder = ImageCaptureConfig.Builder.fromConfig(userConfig);
        // Ensure we're using the combined configuration (user config + defaults)
        mConfig = (ImageCaptureConfig) getUseCaseConfig();
        mCaptureMode = mConfig.getCaptureMode();
        mFlashMode = mConfig.getFlashMode();

        mCaptureProcessor = mConfig.getCaptureProcessor(null);
        mMaxCaptureStages = mConfig.getMaxCaptureStages(MAX_IMAGES);
        if (mMaxCaptureStages < 1) {
            throw new IllegalArgumentException(
                    "Maximum outstanding image count must be at least 1");
        }

        Integer bufferFormat = mConfig.getBufferFormat(null);
        if (bufferFormat != null) {
            if (mCaptureProcessor != null) {
                throw new IllegalArgumentException(
                        "Cannot set buffer format with CaptureProcessor defined.");
            } else {
                setImageFormat(bufferFormat);
            }
        } else {
            if (mCaptureProcessor != null) {
                setImageFormat(ImageFormat.YUV_420_888);
            } else {
                setImageFormat(ImageReaderFormatRecommender.chooseCombo().imageCaptureFormat());
            }
        }

        mCaptureBundle = mConfig.getCaptureBundle(CaptureBundles.singleDefaultCaptureBundle());

        mIoExecutor = mConfig.getIoExecutor(CameraXExecutors.ioExecutor());

        if (mCaptureMode == CaptureMode.MAX_QUALITY) {
            mEnableCheck3AConverged = true; // check 3A convergence in MAX_QUALITY mode
        } else if (mCaptureMode == CaptureMode.MIN_LATENCY) {
            mEnableCheck3AConverged = false; // skip 3A convergence in MIN_LATENCY mode
        }

        CaptureConfig.Builder captureBuilder = CaptureConfig.Builder.createFrom(mConfig);
        mCaptureConfig = captureBuilder.build();
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    SessionConfig.Builder createPipeline(@NonNull String cameraId,
            @NonNull ImageCaptureConfig config, @NonNull Size resolution) {
        Threads.checkMainThread();
        SessionConfig.Builder sessionConfigBuilder = SessionConfig.Builder.createFrom(config);
        sessionConfigBuilder.addRepeatingCameraCaptureCallback(mSessionCallbackChecker);

        mProcessingImageResultThread = new HandlerThread("OnImageAvailableHandlerThread");
        mProcessingImageResultThread.start();
        mProcessingImageResultHandler = new Handler(mProcessingImageResultThread.getLooper());

        // Setup the ImageReader to do processing
        if (mCaptureProcessor != null) {
            // TODO: To allow user to use an Executor for the image processing.
            ProcessingImageReader processingImageReader =
                    new ProcessingImageReader(
                            resolution.getWidth(),
                            resolution.getHeight(),
                            getImageFormat(), mMaxCaptureStages,
                            mProcessingImageResultHandler,
                            getCaptureBundle(CaptureBundles.singleDefaultCaptureBundle()),
                            mCaptureProcessor);
            mMetadataMatchingCaptureCallback = processingImageReader.getCameraCaptureCallback();
            mImageReader = processingImageReader;
        } else {
            MetadataImageReader metadataImageReader = new MetadataImageReader(resolution.getWidth(),
                    resolution.getHeight(), getImageFormat(), MAX_IMAGES,
                    mProcessingImageResultHandler);
            mMetadataMatchingCaptureCallback = metadataImageReader.getCameraCaptureCallback();
            mImageReader = metadataImageReader;
        }

        mImageReader.setOnImageAvailableListener(
                new ImageReaderProxy.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReaderProxy imageReader) {
                        ImageProxy image = null;
                        try {
                            image = imageReader.acquireLatestImage();
                        } catch (IllegalStateException e) {
                            Log.e(TAG, "Failed to acquire latest image.", e);
                        } finally {
                            if (image != null) {
                                // Call the head request listener to process the captured image.
                                ImageCaptureRequest imageCaptureRequest;
                                if ((imageCaptureRequest = mImageCaptureRequests.peek()) != null) {
                                    SingleCloseImageProxy wrappedImage = new SingleCloseImageProxy(
                                            image);
                                    wrappedImage.addOnImageCloseListener(mOnImageCloseListener);
                                    imageCaptureRequest.dispatchImage(wrappedImage);
                                } else {
                                    // Discard the image if we have no requests.
                                    image.close();
                                }
                            }
                        }
                    }
                },
                mProcessingImageResultHandler);

        mDeferrableSurface = new ImmediateSurface(mImageReader.getSurface());
        sessionConfigBuilder.addNonRepeatingSurface(mDeferrableSurface);

        sessionConfigBuilder.addErrorListener(new SessionConfig.ErrorListener() {
            @Override
            public void onError(@NonNull SessionConfig sessionConfig,
                    @NonNull SessionConfig.SessionError error) {
                clearPipeline();
                // Ensure the bound camera has not changed before resetting.
                // TODO(b/143915543): Ensure this never gets called by a camera that is not bound
                //  to this use case so we don't need to do this check.
                if (isCurrentlyBoundCamera(cameraId)) {
                    mSessionConfigBuilder = createPipeline(cameraId, config, resolution);
                    attachToCamera(cameraId, mSessionConfigBuilder.build());
                    notifyReset();
                }
            }
        });

        return sessionConfigBuilder;
    }

    /**
     * Clear the internal pipeline so that the pipeline can be set up again.
     */
    void clearPipeline() {
        Threads.checkMainThread();
        DeferrableSurface deferrableSurface = mDeferrableSurface;
        mDeferrableSurface = null;
        ImageReaderProxy imageReaderProxy = mImageReader;
        mImageReader = null;
        HandlerThread handlerThread = mProcessingImageResultThread;

        if (deferrableSurface != null) {
            deferrableSurface.setOnSurfaceDetachedListener(
                    CameraXExecutors.mainThreadExecutor(),
                    new DeferrableSurface.OnSurfaceDetachedListener() {
                        @Override
                        public void onSurfaceDetached() {
                            if (imageReaderProxy != null) {
                                imageReaderProxy.close();
                            }

                            // Close the handlerThread after the ImageReader was closed.
                            handlerThread.quitSafely();
                        }
                    });
        }
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    @Nullable
    @RestrictTo(Scope.LIBRARY_GROUP)
    protected UseCaseConfig.Builder<?, ?, ?> getDefaultBuilder(LensFacing lensFacing) {
        ImageCaptureConfig defaults = CameraX.getDefaultUseCaseConfig(
                ImageCaptureConfig.class, lensFacing);
        if (defaults != null) {
            return ImageCaptureConfig.Builder.fromConfig(defaults);
        }

        return null;
    }

    private CameraControlInternal getCurrentCameraControl() {
        String cameraId = getBoundCameraId();
        return getCameraControl(cameraId);
    }

    /** Configures flash mode to CameraControlInternal once it is ready. */
    @Override
    protected void onCameraControlReady(@NonNull String cameraId) {
        getCameraControl(cameraId).setFlashMode(mFlashMode);
    }

    /**
     * Get the flash mode.
     *
     * @return the {@link FlashMode}.
     */
    @NonNull
    public FlashMode getFlashMode() {
        return mFlashMode;
    }

    /**
     * Set the flash mode.
     *
     * <p>The flash control for the subsequent photo capture requests. See
     * {@link FlashMode} for the optional settings. Applications can check if flash is available
     * and update UI component if necessary via {@link CameraInfo#isFlashAvailable()}. If no
     * flash is available, then calling this API will take no effect for the subsequent photo
     * capture requests and they will act like {@link FlashMode#OFF}.
     *
     * <p>When the torch is enabled via {@link CameraControl#enableTorch(boolean)}, the torch
     * will remain enabled during photo capture regardless of {@link FlashMode} setting. When
     * the torch is disabled, flash will function as specified by {@link #setFlashMode(FlashMode)}.
     *
     * @param flashMode the {@link FlashMode}.
     */
    public void setFlashMode(@NonNull FlashMode flashMode) {
        this.mFlashMode = flashMode;
        getCurrentCameraControl().setFlashMode(flashMode);
    }

    /**
     * Sets target aspect ratio.
     *
     * <p>This sets the cropping rectangle returned by {@link ImageProxy#getCropRect()} returned
     * from {@link ImageCapture#takePicture(Executor, OnImageCapturedCallback)}.
     *
     * <p>This crops the saved image when calling
     * {@link ImageCapture#takePicture(File, Executor, OnImageSavedCallback)} or
     * {@link ImageCapture#takePicture(File, Metadata, Executor, OnImageSavedCallback)}.
     *
     * <p>Cropping occurs around the center of the image and as though it were in the target
     * rotation.
     *
     * @param aspectRatio New target aspect ratio.
     */
    public void setTargetAspectRatioCustom(@NonNull Rational aspectRatio) {
        ImageOutputConfig oldConfig = (ImageOutputConfig) getUseCaseConfig();
        Rational oldRatio = oldConfig.getTargetAspectRatioCustom(null);
        if (!aspectRatio.equals(oldRatio)) {
            mUseCaseConfigBuilder.setTargetAspectRatioCustom(aspectRatio);
            updateUseCaseConfig(mUseCaseConfigBuilder.build());
            mConfig = (ImageCaptureConfig) getUseCaseConfig();

            // TODO(b/122846516): Reconfigure capture session if the ratio is changed drastically.
        }
    }

    /**
     * Sets the desired rotation of the output image.
     *
     * <p>This will affect the EXIF rotation metadata in images saved by takePicture calls and the
     * rotation value returned by {@link OnImageCapturedCallback}.
     *
     * <p>In most cases this should be set to the current rotation returned by {@link
     * Display#getRotation()}.  In that case, the output rotation from takePicture calls will be the
     * rotation, which if applied to the output image, will make it match the display orientation.
     *
     * <p>While rotation can also be set via
     * {@link ImageCaptureConfig.Builder#setTargetRotation(int)}, using
     * {@link ImageCapture#setTargetRotation(int)} allows the target rotation to be set dynamically.
     * This can be useful if an app locks itself to portrait, and uses the orientation sensor
     * to set rotation to take landscape images when the device is rotated.
     *
     * <p>If no target rotation is set by the application, it is set to the value of
     * {@link Display#getRotation()} of the default display at the time the
     * use case is created.
     *
     * <p>takePicture uses the target rotation at the time it begins executing (which may be delayed
     * waiting on a previous takePicture call to complete).
     *
     * @param rotation Target rotation of the output image, expressed as one of
     *                 {@link Surface#ROTATION_0}, {@link Surface#ROTATION_90},
     *                 {@link Surface#ROTATION_180}, or {@link Surface#ROTATION_270}.
     */
    public void setTargetRotation(@RotationValue int rotation) {
        ImageOutputConfig oldConfig = (ImageOutputConfig) getUseCaseConfig();
        int oldRotation = oldConfig.getTargetRotation(ImageOutputConfig.INVALID_ROTATION);
        if (oldRotation == ImageOutputConfig.INVALID_ROTATION || oldRotation != rotation) {
            mUseCaseConfigBuilder.setTargetRotation(rotation);
            updateUseCaseConfig(mUseCaseConfigBuilder.build());
            mConfig = (ImageCaptureConfig) getUseCaseConfig();

            // TODO(b/122846516): Update session configuration and possibly reconfigure session.
        }
    }

    /**
     * Captures a new still image for in memory access.
     *
     * <p>The callback will be called only once for every invocation of this method. The listener
     * is responsible for calling {@link Image#close()} on the returned image.
     *
     * @param executor The executor in which the callback methods will be run.
     * @param callback Callback to be invoked for the newly captured image
     */
    @SuppressLint("LambdaLast") // Maybe remove after https://issuetracker.google.com/135275901
    public void takePicture(@NonNull Executor executor,
            final @NonNull OnImageCapturedCallback callback) {
        if (Looper.getMainLooper() != Looper.myLooper()) {
            CameraXExecutors.mainThreadExecutor().execute(() -> takePicture(executor, callback));
            return;
        }

        sendImageCaptureRequest(executor, callback);
    }

    /**
     * Captures a new still image and saves to a file.
     *
     * <p>The listener's callback will be called only once for every invocation of this method.
     *
     * @param saveLocation       Location to store the newly captured image.
     * @param executor           The executor in which the listener callback methods will be run.
     * @param imageSavedListener Listener to be called for the newly captured image.
     */
    @SuppressLint("LambdaLast") // Maybe remove after https://issuetracker.google.com/135275901
    public void takePicture(@NonNull File saveLocation,
            @NonNull Executor executor,
            @NonNull OnImageSavedCallback imageSavedListener) {
        takePicture(saveLocation, EMPTY_METADATA, executor, imageSavedListener);
    }

    /**
     * Captures a new still image and saves to a file along with application specified metadata.
     *
     * <p>The callback will be called only once for every invocation of this method.
     *
     * <p>This function accepts metadata as a parameter from application code.  For JPEGs, this
     * metadata will be included in the EXIF.
     *
     * @param saveLocation       Location to store the newly captured image.
     * @param metadata           Metadata to be stored with the saved image. For JPEG this will
     *                           be included in the EXIF.
     * @param executor           The executor in which the callback methods will be run.
     * @param imageSavedCallback Callback to be called for the newly captured image.
     */
    @SuppressLint("LambdaLast") // Maybe remove after https://issuetracker.google.com/135275901
    public void takePicture(
            final @NonNull File saveLocation,
            final @NonNull Metadata metadata, @NonNull Executor executor,
            final @NonNull OnImageSavedCallback imageSavedCallback) {
        if (Looper.getMainLooper() != Looper.myLooper()) {
            CameraXExecutors.mainThreadExecutor().execute(
                    () -> takePicture(saveLocation, metadata, executor, imageSavedCallback));
            return;
        }

        /*
         * We need to chain the following callbacks to save the image to disk:
         *
         * +-----------------------+
         * |                       |
         * |ImageCapture.          |
         * |OnImageCapturedCallback|
         * |                       |
         * +-----------+-----------+
         *             |
         *             |
         * +-----------v-----------+      +----------------------+
         * |                       |      |                      |
         * | ImageSaver.           |      | ImageCapture.        |
         * | OnImageSavedCallback  +------> OnImageSavedCallback |
         * |                       |      |                      |
         * +-----------------------+      +----------------------+
         */

        // Convert the ImageSaver.OnImageSavedCallback to ImageCapture.OnImageSavedCallback
        final ImageSaver.OnImageSavedCallback imageSavedCallbackWrapper =
                new ImageSaver.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(File file) {
                        imageSavedCallback.onImageSaved(file);
                    }

                    @Override
                    public void onError(@ImageSaver.SaveError int error, String message,
                            @Nullable Throwable cause) {
                        @ImageCaptureError int imageCaptureError = ImageCaptureError.UNKNOWN_ERROR;
                        switch (error) {
                            case ImageSaver.SaveError.FILE_IO_FAILED:
                                imageCaptureError = ImageCaptureError.FILE_IO_ERROR;
                                break;
                            default:
                                // Keep the imageCaptureError as UNKNOWN_ERROR
                                break;
                        }

                        imageSavedCallback.onError(imageCaptureError, message, cause);
                    }
                };

        // Wrap the ImageCapture.OnImageSavedCallback with an OnImageCapturedCallback so it can
        // be put into the capture request queue
        OnImageCapturedCallback imageCaptureCallbackWrapper =
                new OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy image, int rotationDegrees) {
                        mIoExecutor.execute(
                                new ImageSaver(
                                        image,
                                        saveLocation,
                                        rotationDegrees,
                                        metadata.isReversedHorizontal(),
                                        metadata.isReversedVertical(),
                                        metadata.getLocation(),
                                        executor,
                                        imageSavedCallbackWrapper));
                    }

                    @Override
                    public void onError(@ImageCaptureError int error, @NonNull String message,
                            @Nullable Throwable cause) {
                        imageSavedCallback.onError(error, message, cause);
                    }
                };

        // Always use the mainThreadExecutor for the initial callback so we don't need to double
        // post to another thread
        sendImageCaptureRequest(CameraXExecutors.mainThreadExecutor(), imageCaptureCallbackWrapper);
    }

    @UiThread
    @Override
    public void onStateOffline(@NonNull String cameraId) {
        super.onStateOffline(cameraId);
        abortImagecaptureRequests();
    }

    private void abortImagecaptureRequests() {
        Throwable throwable = new CameraClosedException("Camera is closed.");
        for (ImageCaptureRequest captureRequest :
                mImageCaptureRequests) {
            captureRequest.notifyCallbackError(
                    getError(throwable),
                    throwable.getMessage(), throwable);
        }
        mImageCaptureRequests.clear();
    }

    @UiThread
    private void sendImageCaptureRequest(
            @Nullable Executor listenerExecutor, OnImageCapturedCallback callback) {

        String cameraId;
        try {
            // TODO(b/143734846): From here on, the image capture request should be
            //  self-contained and use this camera ID for everything. Currently the pre-capture
            //  sequence does not follow this approach and could fail if this use case is unbound
            //  or unbound to a different camera in the middle of pre-capture.
            cameraId = getBoundCameraId();
        } catch (Throwable e) {
            // Not bound. Notify callback.
            callback.onError(ImageCaptureError.INVALID_CAMERA,
                    "Not bound to a valid Camera [" + ImageCapture.this + "]", e);
            return;
        }

        // Get the relative rotation or default to 0 if the camera info is unavailable
        int relativeRotation = 0;
        try {
            CameraInfoInternal cameraInfoInternal = CameraX.getCameraInfo(cameraId);
            relativeRotation =
                    cameraInfoInternal.getSensorRotationDegrees(
                            mConfig.getTargetRotation(Surface.ROTATION_0));
        } catch (CameraInfoUnavailableException e) {
            Log.e(TAG, "Unable to retrieve camera sensor orientation.", e);
        }

        Rational targetRatio = mConfig.getTargetAspectRatioCustom(null);
        targetRatio = ImageUtil.rotate(targetRatio, relativeRotation);

        mImageCaptureRequests.offer(
                new ImageCaptureRequest(relativeRotation, targetRatio, listenerExecutor, callback));
        if (mImageCaptureRequests.size() == 1) {
            issueImageCaptureRequests();
        }
    }

    /** Issues saved ImageCaptureRequest. */
    @UiThread
    void issueImageCaptureRequests() {
        if (mImageCaptureRequests.isEmpty()) {
            return;
        }
        takePictureInternal();
    }

    /**
     * The take picture flow.
     *
     * <p>There are three steps to take a picture.
     *
     * <p>(1) Pre-take picture, which will trigger af/ae scan or open torch if necessary. Then check
     * 3A converged if necessary.
     *
     * <p>(2) Issue take picture single request.
     *
     * <p>(3) Post-take picture, which will cancel af/ae scan or close torch if necessary.
     */
    private void takePictureInternal() {
        final TakePictureState state = new TakePictureState();

        FutureChain.from(preTakePicture(state))
                .transformAsync(v -> issueTakePicture(), mExecutor)
                .addCallback(
                        new FutureCallback<Void>() {
                            @Override
                            public void onSuccess(Void result) {
                                postTakePicture(state);
                            }

                            @Override
                            public void onFailure(Throwable throwable) {
                                Log.e(TAG, "takePictureInternal onFailure", throwable);
                                postTakePicture(state);

                                // To handle the error and issue the next capture request
                                // when the capture stages have any fail.
                                CameraXExecutors.mainThreadExecutor().execute(() -> {
                                    ImageCaptureRequest request = mImageCaptureRequests.poll();

                                    // It could happens if we clear the mImageCaptureRequests queue
                                    // in the middle of takePicture.
                                    if (request == null) {
                                        return;
                                    }

                                    request.notifyCallbackError(getError(throwable),
                                            (throwable != null) ? throwable.getMessage()
                                                    : "Unknown error", throwable);
                                    // Handle the next request.
                                    issueImageCaptureRequests();
                                });
                            }
                        },
                        mExecutor);
    }

    @NonNull
    @Override
    public String toString() {
        return TAG + ":" + getName();
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    @ImageCaptureError
    int getError(Throwable throwable) {
        if (throwable instanceof CameraClosedException) {
            return ImageCaptureError.CAMERA_CLOSED;
        } else if (throwable instanceof CaptureFailedException) {
            return ImageCaptureError.CAPTURE_FAILED;
        } else {
            return ImageCaptureError.UNKNOWN_ERROR;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    @Override
    public void clear() {
        clearPipeline();
        mExecutor.shutdown();
        super.clear();
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @NonNull
    @Override
    @RestrictTo(Scope.LIBRARY_GROUP)
    protected Map<String, Size> onSuggestedResolutionUpdated(
            @NonNull Map<String, Size> suggestedResolutionMap) {
        String cameraId = getBoundCameraId();
        Size resolution = suggestedResolutionMap.get(cameraId);
        if (resolution == null) {
            throw new IllegalArgumentException(
                    "Suggested resolution map missing resolution for camera " + cameraId);
        }

        if (mImageReader != null) {
            if (mImageReader.getHeight() == resolution.getHeight()
                    && mImageReader.getWidth() == resolution.getWidth()) {
                // Resolution does not need to be updated. Return early.
                return suggestedResolutionMap;
            }
            mImageReader.close();
        }

        mSessionConfigBuilder = createPipeline(cameraId, mConfig, resolution);

        attachToCamera(cameraId, mSessionConfigBuilder.build());

        // In order to speed up the take picture process, notifyActive at an early stage to
        // attach the session capture callback to repeating and get capture result all the time.
        notifyActive();

        return suggestedResolutionMap;
    }

    final OnImageCloseListener mOnImageCloseListener = new OnImageCloseListener() {
        /**
         * {@inheritDoc}
         *
         * <p>Issues next image capture request when dispatched image is closed, which can ensure
         * the image buffer in ImageReader is always available.
         */
        @Override
        public void onImageClose(ImageProxy image) {
            if (Looper.getMainLooper() != Looper.myLooper()) {
                CameraXExecutors.mainThreadExecutor().execute(() -> onImageClose(image));
                return;
            }
            mImageCaptureRequests.poll();
            issueImageCaptureRequests();
        }
    };

    /**
     * Routine before taking picture.
     *
     * <p>For example, trigger 3A scan, open torch and check 3A converged if necessary.
     */
    private ListenableFuture<Void> preTakePicture(final TakePictureState state) {
        return FutureChain.from(getPreCaptureStateIfNeeded())
                .transformAsync(captureResult -> {
                    state.mPreCaptureState = captureResult;
                    triggerAfIfNeeded(state);

                    if (isFlashRequired(state)) {
                        state.mIsFlashTriggered = true;
                        triggerAePrecapture(state);
                    }
                    return check3AConverged(state);
                }, mExecutor)
                // Ignore the 3A convergence result.
                .transform(is3AConverged -> null, mExecutor);
    }

    /**
     * Routine after picture was taken.
     *
     * <p>For example, cancel 3A scan, close torch if necessary.
     */
    void postTakePicture(final TakePictureState state) {
        mExecutor.execute(() -> ImageCapture.this.cancelAfAeTrigger(state));
    }

    /**
     * Gets a capture result or not according to current configuration.
     *
     * <p>Conditions to get a capture result.
     *
     * <p>(1) The enableCheck3AConverged is enabled because it needs to know current AF mode and
     * state.
     *
     * <p>(2) The flashMode is AUTO because it needs to know the current AE state.
     */
    // Currently this method is used to prevent there is no repeating surface to get capture result.
    // If app is in min-latency mode and flash ALWAYS/OFF mode, it can still take picture without
    // checking the capture result. Remove this check once no repeating surface issue is fixed.
    private ListenableFuture<CameraCaptureResult> getPreCaptureStateIfNeeded() {
        if (mEnableCheck3AConverged || getFlashMode() == FlashMode.AUTO) {
            return mSessionCallbackChecker.checkCaptureResult(
                    new CaptureCallbackChecker.CaptureResultChecker<CameraCaptureResult>() {
                        @Override
                        public CameraCaptureResult check(
                                @NonNull CameraCaptureResult captureResult) {
                            return captureResult;
                        }
                    });
        }
        return Futures.immediateFuture(null);
    }

    boolean isFlashRequired(TakePictureState state) {
        switch (getFlashMode()) {
            case ON:
                return true;
            case AUTO:
                return state.mPreCaptureState.getAeState() == AeState.FLASH_REQUIRED;
            case OFF:
                return false;
        }
        throw new AssertionError(getFlashMode());
    }

    ListenableFuture<Boolean> check3AConverged(TakePictureState state) {
        // Besides enableCheck3AConverged == true (MAX_QUALITY), if flash is triggered we also need
        // to
        // wait for 3A convergence.
        if (!mEnableCheck3AConverged && !state.mIsFlashTriggered) {
            return Futures.immediateFuture(false);
        }

        // if current capture result shows 3A is converged, no need to check upcoming capture
        // result.
        if (is3AConverged(state.mPreCaptureState)) {
            return Futures.immediateFuture(true);
        }

        return mSessionCallbackChecker.checkCaptureResult(
                new CaptureCallbackChecker.CaptureResultChecker<Boolean>() {
                    @Override
                    public Boolean check(@NonNull CameraCaptureResult captureResult) {
                        if (is3AConverged(captureResult)) {
                            return true;
                        }
                        // Return null to continue check.
                        return null;
                    }
                },
                CHECK_3A_TIMEOUT_IN_MS,
                false);
    }

    boolean is3AConverged(CameraCaptureResult captureResult) {
        if (captureResult == null) {
            return false;
        }

        // If afMode is CAF, don't check af locked to speed up.
        // If afMode is OFF or UNKNOWN , no need for waiting.
        // otherwise wait until af is locked or focused.
        boolean isAfReady = (captureResult.getAfMode() == AfMode.ON_CONTINUOUS_AUTO
                || captureResult.getAfMode() == AfMode.OFF
                || captureResult.getAfMode() == AfMode.UNKNOWN
                || captureResult.getAfState() == AfState.FOCUSED
                || captureResult.getAfState() == AfState.LOCKED_FOCUSED
                || captureResult.getAfState() == AfState.LOCKED_NOT_FOCUSED);

        // Unknown means cannot get valid state from CaptureResult
        boolean isAeReady = captureResult.getAeState() == AeState.CONVERGED
                || captureResult.getAeState() == AeState.UNKNOWN;

        // Unknown means cannot get valid state from CaptureResult
        boolean isAwbReady = captureResult.getAwbState() == AwbState.CONVERGED
                || captureResult.getAwbState() == AwbState.UNKNOWN;

        return (isAfReady && isAeReady && isAwbReady);
    }

    /**
     * Issues the AF scan if needed.
     *
     * <p>If enableCheck3AConverged is disabled or it is in CAF mode, AF scan should not be
     * triggered. Trigger AF scan only in {@link AfMode#ON_MANUAL_AUTO} and current AF state is
     * {@link AfState#INACTIVE}. If the AF mode is {@link AfMode#ON_MANUAL_AUTO} and AF state is not
     * inactive, it means that a manual or auto focus request may be in progress or completed.
     */
    void triggerAfIfNeeded(TakePictureState state) {
        if (mEnableCheck3AConverged
                && state.mPreCaptureState.getAfMode() == AfMode.ON_MANUAL_AUTO
                && state.mPreCaptureState.getAfState() == AfState.INACTIVE) {
            triggerAf(state);
        }
    }

    /** Issues a request to start auto focus scan. */
    private void triggerAf(TakePictureState state) {
        state.mIsAfTriggered = true;
        getCurrentCameraControl().triggerAf();
    }

    /** Issues a request to start auto exposure scan. */
    void triggerAePrecapture(TakePictureState state) {
        state.mIsAePrecaptureTriggered = true;
        getCurrentCameraControl().triggerAePrecapture();
    }

    /** Issues a request to cancel auto focus and/or auto exposure scan. */
    void cancelAfAeTrigger(TakePictureState state) {
        if (!state.mIsAfTriggered && !state.mIsAePrecaptureTriggered) {
            return;
        }
        getCurrentCameraControl()
                .cancelAfAeTrigger(state.mIsAfTriggered, state.mIsAePrecaptureTriggered);
        state.mIsAfTriggered = false;
        state.mIsAePrecaptureTriggered = false;
    }

    /** Issues a take picture request. */
    ListenableFuture<Void> issueTakePicture() {
        final List<ListenableFuture<Void>> futureList = new ArrayList<>();
        final List<CaptureConfig> captureConfigs = new ArrayList<>();

        CaptureBundle captureBundle;
        if (mCaptureProcessor != null) {
            // If the Processor is provided, check if we have valid CaptureBundle and update
            // ProcessingImageReader before actually issuing a take picture request.
            captureBundle = getCaptureBundle(null);

            if (captureBundle == null) {
                return Futures.immediateFailedFuture(new IllegalArgumentException(
                        "ImageCapture cannot set empty CaptureBundle."));
            }

            if (captureBundle.getCaptureStages().size() > mMaxCaptureStages) {
                return Futures.immediateFailedFuture(new IllegalArgumentException(
                        "ImageCapture has CaptureStages > Max CaptureStage size"));
            }

            ((ProcessingImageReader) mImageReader).setCaptureBundle(captureBundle);
        } else {
            captureBundle = getCaptureBundle(CaptureBundles.singleDefaultCaptureBundle());
            if (captureBundle.getCaptureStages().size() > 1) {
                return Futures.immediateFailedFuture(new IllegalArgumentException(
                        "ImageCapture have no CaptureProcess set with CaptureBundle size > 1."));
            }
        }

        for (final CaptureStage captureStage : captureBundle.getCaptureStages()) {
            final CaptureConfig.Builder builder = new CaptureConfig.Builder();
            builder.setTemplateType(mCaptureConfig.getTemplateType());
            builder.addImplementationOptions(mCaptureConfig.getImplementationOptions());
            builder.addAllCameraCaptureCallbacks(
                    mSessionConfigBuilder.getSingleCameraCaptureCallbacks());

            builder.addSurface(mDeferrableSurface);

            builder.addImplementationOptions(
                    captureStage.getCaptureConfig().getImplementationOptions());
            builder.setTag(captureStage.getCaptureConfig().getTag());
            builder.addCameraCaptureCallback(mMetadataMatchingCaptureCallback);

            ListenableFuture<Void> future = CallbackToFutureAdapter.getFuture(
                    completer -> {
                        CameraCaptureCallback completerCallback = new CameraCaptureCallback() {
                            @Override
                            public void onCaptureCompleted(
                                    @NonNull CameraCaptureResult result) {
                                completer.set(null);
                            }

                            @Override
                            public void onCaptureFailed(
                                    @NonNull CameraCaptureFailure failure) {
                                String msg = "Capture request failed with reason "
                                        + failure.getReason();
                                completer.setException(new CaptureFailedException(msg));
                            }

                            @Override
                            public void onCaptureCancelled() {
                                String msg = "Capture request is cancelled because "
                                        + "camera is closed";
                                completer.setException(new CameraClosedException(msg));
                            }
                        };
                        builder.addCameraCaptureCallback(completerCallback);

                        captureConfigs.add(builder.build());
                        return "issueTakePicture[stage=" + captureStage.getId() + "]";
                    });
            futureList.add(future);

        }

        getCurrentCameraControl().submitCaptureRequests(captureConfigs);
        return Futures.transform(Futures.allAsList(futureList),
                input -> null, CameraXExecutors.directExecutor());
    }

    /** This exception is thrown when request is failed (reported by framework) */
    static final class CaptureFailedException extends RuntimeException {
        /** @hide */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        CaptureFailedException(String s, Throwable e) {
            super(s, e);
        }

        /** @hide */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        CaptureFailedException(String s) {
            super(s);
        }
    }


    private CaptureBundle getCaptureBundle(CaptureBundle defaultCaptureBundle) {
        List<CaptureStage> captureStages = mCaptureBundle.getCaptureStages();
        if (captureStages == null || captureStages.isEmpty()) {
            return defaultCaptureBundle;
        }

        return CaptureBundles.createCaptureBundle(captureStages);
    }

    /**
     * Describes the error that occurred during an image capture operation (such as {@link
     * ImageCapture#takePicture(Executor, OnImageCapturedCallback)}).
     *
     * <p>This is a parameter sent to the error callback functions set in listeners such as {@link
     * ImageCapture.OnImageSavedCallback#onError(int, String, Throwable)}.
     */
    @IntDef({ImageCaptureError.UNKNOWN_ERROR, ImageCaptureError.FILE_IO_ERROR,
            ImageCaptureError.CAPTURE_FAILED, ImageCaptureError.CAMERA_CLOSED,
            ImageCaptureError.INVALID_CAMERA})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImageCaptureError {
        /**
         * An unknown error occurred.
         *
         * <p>See message parameter in onError callback or log for more details.
         */
        int UNKNOWN_ERROR = 0;
        /**
         * An error occurred while attempting to read or write a file, such as when saving an image
         * to a File.
         */
        int FILE_IO_ERROR = 1;

        /**
         * An error reported by camera framework indicating the capture request is failed.
         */
        int CAPTURE_FAILED = 2;

        /**
         * An error indicating the request cannot be done due to camera is closed.
         */
        int CAMERA_CLOSED = 3;

        /**
         * An error indicating this ImageCapture is not bound to a valid camera.
         */
        int INVALID_CAMERA = 4;
    }

    /**
     * Capture mode options for ImageCapture. A picture will always be taken regardless of
     * mode, and the mode will be used on devices that support it.
     */
    public enum CaptureMode {
        /**
         * Optimizes capture pipeline to prioritize image quality over latency. When the capture
         * mode is set to MAX_QUALITY, images may take longer to capture.
         */
        @SuppressLint("MinMaxConstant") // It is a name but not a constant number.
        MAX_QUALITY,
        /**
         * Optimizes capture pipeline to prioritize latency over image quality. When the capture
         * mode is set to MIN_LATENCY, images may capture faster but the image quality may be
         * reduced.
         */
        @SuppressLint("MinMaxConstant") // It is a name but not a constant number.
        MIN_LATENCY
    }

    /** Listener containing callbacks for image file I/O events. */
    public interface OnImageSavedCallback {
        /** Called when an image has been successfully saved. */
        void onImageSaved(@NonNull File file);

        /** Called when an error occurs while attempting to save an image. */
        void onError(
                @ImageCaptureError int imageCaptureError,
                @NonNull String message,
                @Nullable Throwable cause);
    }

    /**
     * Callback for when an image capture has completed.
     */
    public abstract static class OnImageCapturedCallback {
        /**
         * Callback for when the image has been captured.
         *
         * <p>The application is responsible for calling {@link ImageProxy#close()} to close the
         * image.
         *
         * <p>The image is of format {@link ImageFormat#JPEG}, queryable via
         * {@link ImageProxy#getFormat()}.
         *
         * <p>The image is provided as captured by the underlying {@link ImageReader} without
         * rotation applied.  rotationDegrees describes the magnitude of clockwise rotation, which
         * if applied to the image will make it match the currently configured target rotation.
         *
         * <p>For example, if the current target rotation is set to the display rotation,
         * rotationDegrees is the rotation to apply to the image to match the display orientation.
         * A rotation of 90 degrees would mean rotating the image 90 degrees clockwise produces an
         * image that will match the display orientation.
         *
         * <p>See also {@link ImageCaptureConfig.Builder#setTargetRotation(int)} and
         * {@link #setTargetRotation(int)}.
         *
         * @param image           The captured image
         * @param rotationDegrees The rotation which if applied to the image will make it match the
         *                        current target rotation. rotationDegrees is expressed as one of
         *                        {@link Surface#ROTATION_0}, {@link Surface#ROTATION_90},
         *                        {@link Surface#ROTATION_180}, or {@link Surface#ROTATION_270}.
         */
        public void onCaptureSuccess(@NonNull ImageProxy image, int rotationDegrees) {
            image.close();
        }

        /** Callback for when an error occurred during image capture. */
        public void onError(@ImageCaptureError int imageCaptureError, @NonNull String message,
                @Nullable Throwable cause) {
        }
    }

    /**
     * Provides a base static default configuration for the ImageCapture
     *
     * <p>These values may be overridden by the implementation. They only provide a minimum set of
     * defaults that are implementation independent.
     *
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    public static final class Defaults
            implements ConfigProvider<ImageCaptureConfig> {
        private static final CaptureMode DEFAULT_CAPTURE_MODE = CaptureMode.MIN_LATENCY;
        private static final FlashMode DEFAULT_FLASH_MODE = FlashMode.OFF;
        private static final int DEFAULT_SURFACE_OCCUPANCY_PRIORITY = 4;

        private static final ImageCaptureConfig DEFAULT_CONFIG;

        static {
            ImageCaptureConfig.Builder builder =
                    new ImageCaptureConfig.Builder()
                            .setCaptureMode(DEFAULT_CAPTURE_MODE)
                            .setFlashMode(DEFAULT_FLASH_MODE)
                            .setSurfaceOccupancyPriority(DEFAULT_SURFACE_OCCUPANCY_PRIORITY);

            DEFAULT_CONFIG = builder.build();
        }

        @Override
        public ImageCaptureConfig getConfig(LensFacing lensFacing) {
            return DEFAULT_CONFIG;
        }
    }

    /** Holder class for metadata that will be saved with captured images. */
    public static final class Metadata {
        /**
         * Indicates an upside down mirroring, equivalent to a horizontal mirroring (reflection)
         * followed by a 180 degree rotation.
         *
         * <p>The reflection is meant to be applied to the upright image (after rotation to the
         * target orientation). When saving the image to file, it is combined with the rotation
         * degrees, to generate the corresponding EXIF orientation value.
         */
        private boolean mIsReversedHorizontal;
        /**
         * Indicates a left-right mirroring (reflection).
         *
         * <p>The reflection is meant to be applied to the upright image (after rotation to the
         * target orientation). When saving the image to file, it is combined with the rotation
         * degrees, to generate the corresponding EXIF orientation value.
         */
        private boolean mIsReversedVertical;
        /** Data representing a geographic location. */
        @Nullable
        private Location mLocation;

        /**
         * Gets upside-down mirroring of the capture.
         *
         * @return true if the capture is upside-down.
         */
        public boolean isReversedHorizontal() {
            return mIsReversedHorizontal;
        }

        /**
         * Sets upside-down mirroring of the capture.
         *
         * @param isReversedHorizontal true if the capture is upside-down.
         */
        public void setReversedHorizontal(boolean isReversedHorizontal) {
            mIsReversedHorizontal = isReversedHorizontal;
        }

        /**
         * Gets left-right mirroring of the capture.
         *
         * @return true if the capture is left-right mirrored.
         */
        public boolean isReversedVertical() {
            return mIsReversedVertical;
        }

        /**
         * Sets left-right mirroring of the capture.
         *
         * @param isReversedVertical true if the capture is left-right mirrored.
         */
        public void setReversedVertical(boolean isReversedVertical) {
            mIsReversedVertical = isReversedVertical;
        }

        /**
         * Gets the geographic location of the capture.
         *
         * @return the geographic location.
         */
        @Nullable
        public Location getLocation() {
            return mLocation;
        }

        /**
         * Sets the geographic location of the capture.
         *
         * @param location the geographic location.
         */
        public void setLocation(@Nullable Location location) {
            mLocation = location;
        }
    }

    /**
     * An intermediate action recorder while taking picture. It is used to restore certain states.
     * For example, cancel AF/AE scan, and close flash light.
     */
    static final class TakePictureState {
        CameraCaptureResult mPreCaptureState = EmptyCameraCaptureResult.create();
        boolean mIsAfTriggered = false;
        boolean mIsAePrecaptureTriggered = false;
        boolean mIsFlashTriggered = false;
    }

    /**
     * A helper class to check camera capture result.
     *
     * <p>CaptureCallbackChecker is an implementation of {@link CameraCaptureCallback} that checks a
     * specified list of condition and sets a ListenableFuture when the conditions have been met. It
     * is mainly used to continuously capture callbacks to detect specific conditions. It also
     * handles the timeout condition if the check condition does not satisfy the given timeout, and
     * returns the given default value if the timeout is met.
     */
    static final class CaptureCallbackChecker extends CameraCaptureCallback {
        private static final long NO_TIMEOUT = 0L;

        /** Capture listeners. */
        private final Set<CaptureResultListener> mCaptureResultListeners = new HashSet<>();

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureResult cameraCaptureResult) {
            deliverCaptureResultToListeners(cameraCaptureResult);
        }

        /**
         * Check the capture results of current session capture callback by giving a {@link
         * CaptureResultChecker}.
         *
         * @param checker a CaptureResult checker that returns an object with type T if the check is
         *                complete, returning null to continue the check process.
         * @param <T>     the type parameter for CaptureResult checker.
         * @return a listenable future for capture result check process.
         */
        <T> ListenableFuture<T> checkCaptureResult(CaptureResultChecker<T> checker) {
            return checkCaptureResult(checker, NO_TIMEOUT, null);
        }

        /**
         * Check the capture results of current session capture callback with timeout limit by
         * giving a {@link CaptureResultChecker}.
         *
         * @param checker     a CaptureResult checker that returns an object with type T if the
         *                    check is
         *                    complete, returning null to continue the check process.
         * @param timeoutInMs used to force stop checking.
         * @param defValue    the default return value if timeout occur.
         * @param <T>         the type parameter for CaptureResult checker.
         * @return a listenable future for capture result check process.
         */
        <T> ListenableFuture<T> checkCaptureResult(
                final CaptureResultChecker<T> checker, final long timeoutInMs, final T defValue) {
            if (timeoutInMs < NO_TIMEOUT) {
                throw new IllegalArgumentException("Invalid timeout value: " + timeoutInMs);
            }
            final long startTimeInMs =
                    (timeoutInMs != NO_TIMEOUT) ? SystemClock.elapsedRealtime() : 0L;

            return CallbackToFutureAdapter.getFuture(
                    completer -> {
                        addListener(
                                new CaptureResultListener() {
                                    @Override
                                    public boolean onCaptureResult(
                                            @NonNull CameraCaptureResult captureResult) {
                                        T result = checker.check(captureResult);
                                        if (result != null) {
                                            completer.set(result);
                                            return true;
                                        } else if (startTimeInMs > 0
                                                && SystemClock.elapsedRealtime() - startTimeInMs
                                                > timeoutInMs) {
                                            completer.set(defValue);
                                            return true;
                                        }
                                        // Return false to continue check.
                                        return false;
                                    }
                                });
                        return "checkCaptureResult";
                    });
        }

        /**
         * Delivers camera capture result to {@link CaptureCallbackChecker#mCaptureResultListeners}.
         */
        private void deliverCaptureResultToListeners(@NonNull CameraCaptureResult captureResult) {
            synchronized (mCaptureResultListeners) {
                Set<CaptureResultListener> removeSet = null;
                for (CaptureResultListener listener : new HashSet<>(mCaptureResultListeners)) {
                    // Remove listener if the callback return true
                    if (listener.onCaptureResult(captureResult)) {
                        if (removeSet == null) {
                            removeSet = new HashSet<>();
                        }
                        removeSet.add(listener);
                    }
                }
                if (removeSet != null) {
                    mCaptureResultListeners.removeAll(removeSet);
                }
            }
        }

        /** Add capture result listener. */
        void addListener(CaptureResultListener listener) {
            synchronized (mCaptureResultListeners) {
                mCaptureResultListeners.add(listener);
            }
        }

        /** An interface to check camera capture result. */
        public interface CaptureResultChecker<T> {

            /**
             * The callback to check camera capture result.
             *
             * @param captureResult the camera capture result.
             * @return the check result, return null to continue checking.
             */
            @Nullable
            T check(@NonNull CameraCaptureResult captureResult);
        }

        /** An interface to listen to camera capture results. */
        private interface CaptureResultListener {

            /**
             * Callback to handle camera capture results.
             *
             * @param captureResult camera capture result.
             * @return true to finish listening, false to continue listening.
             */
            boolean onCaptureResult(@NonNull CameraCaptureResult captureResult);
        }
    }

    private final class ImageCaptureRequest {
        @RotationValue
        int mRotationDegrees;
        Rational mTargetRatio;
        @NonNull
        Executor mListenerExecutor;
        @NonNull
        OnImageCapturedCallback mCallback;

        ImageCaptureRequest(
                @RotationValue int rotationDegrees,
                Rational targetRatio,
                @NonNull Executor executor,
                @NonNull OnImageCapturedCallback callback) {
            mRotationDegrees = rotationDegrees;
            mTargetRatio = targetRatio;
            mListenerExecutor = executor;
            mCallback = callback;
        }

        void dispatchImage(final ImageProxy image) {
            try {
                mListenerExecutor.execute(() -> {
                    Size sourceSize = new Size(image.getWidth(), image.getHeight());
                    if (ImageUtil.isAspectRatioValid(sourceSize, mTargetRatio)) {
                        image.setCropRect(
                                ImageUtil.computeCropRectFromAspectRatio(sourceSize,
                                        mTargetRatio));
                    }

                    mCallback.onCaptureSuccess(image, mRotationDegrees);
                });
            } catch (RejectedExecutionException e) {
                Log.e(TAG, "Unable to post to the supplied executor.");

                // Unable to execute on the supplied executor, close the image.
                image.close();
            }
        }

        void notifyCallbackError(final @ImageCaptureError int imageCaptureError,
                final String message, final Throwable cause) {
            try {
                mListenerExecutor.execute(
                        () -> mCallback.onError(imageCaptureError, message, cause));
            } catch (RejectedExecutionException e) {
                Log.e(TAG, "Unable to post to the supplied executor.");
            }
        }
    }
}
