package com.example.camera2apitextapplication;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {
    List<Cell> allFilesPaths;
    Button btnCapture, btnSave, btnRefresh;
    private TextureView textureView;
    ProgressBar progressBar;
    String applicationStoragePath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/LogisticApplication/";
    String imageFormat = ".jpeg";

    private static final SparseIntArray ORIENTATION = new SparseIntArray();

    static {
        ORIENTATION.append(Surface.ROTATION_0, 90);
        ORIENTATION.append(Surface.ROTATION_90, 0);
        ORIENTATION.append(Surface.ROTATION_180, 270);
        ORIENTATION.append(Surface.ROTATION_270, 180);
    }

    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSessions;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimensions;
    private File file;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        progressBar.setVisibility(ProgressBar.INVISIBLE);
        textureView = (TextureView) findViewById(R.id.textureView);
        assert textureView != null;
        textureView.setSurfaceTextureListener(textureListener);
        btnRefresh = (Button) findViewById(R.id.btnRefresh);
        btnRefresh.setOnClickListener(view -> load());
        btnSave = (Button) findViewById(R.id.btnSave);
        btnSave.setOnClickListener(view -> clearApplicationDirectory());
        btnCapture = (Button) findViewById(R.id.btnCapture);
        btnCapture.setOnClickListener(view -> startCaptureImage());
        createApplicationDirectory();
        clearApplicationDirectory();
    }


    private void startCaptureImage() {
        btnCapture.setClickable(false);
        progressBar.setVisibility(ProgressBar.VISIBLE);
        takePicture();
    }

    private void onImageCapturedAndSavedToFiles() {
        runOnUiThread(() -> {
            btnCapture.setClickable(true);
            load();
            progressBar.setVisibility(ProgressBar.INVISIBLE);
            Toast.makeText(MainActivity.this, "Saved" + file, Toast.LENGTH_SHORT).show();
        });
    }


    //работа с галереей
    //проверить разрешения
    //загрузить изображения
    //создание необходимых массивов путей и файлов

    private void load() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1000);
        } else {
            showImages();
        }
    }

    private void showImages() {

        allFilesPaths = new ArrayList<>();
        allFilesPaths = listAllFiles(applicationStoragePath);

        RecyclerView recyclerView = (RecyclerView) findViewById(R.id.gallery);
        recyclerView.setHasFixedSize(true);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(getApplicationContext(), RecyclerView.HORIZONTAL, false);
        recyclerView.setLayoutManager(layoutManager);

        ArrayList<Cell> cells = prepareData();
        MyAdapter adapter = new MyAdapter(getApplicationContext(), cells);
        recyclerView.setAdapter(adapter);
    }

    private ArrayList<Cell> prepareData() {
        ArrayList<Cell> allImages = new ArrayList<>();
        for (Cell c : allFilesPaths) {
            Cell cell = new Cell();
            cell.setTitle(c.getTitle());
            cell.setPath(c.getPath());
            allImages.add(cell);
        }
        return allImages;
    }

    private List<Cell> listAllFiles(String pathName) {
        List<Cell> allFiles = new ArrayList<>();
        File file = new File(pathName);
        File[] files = file.listFiles();
        if (files != null) {
            for (File f : files) {
                Cell cell = new Cell();
                cell.setTitle(f.getName());
                cell.setPath(f.getAbsolutePath());
                allFiles.add(cell);
            }
        }
        return allFiles;
    }

    //работа с камерой:
    //необходимый коллбэк
    //определить положение камеры на экране,
    //запустить камеру, создать превью, обновить превью,
    //остановить, продолжить, запустить и остановить фоновые процессы
    //сделать фото

    CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int i) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
            try {
                openCamera();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
            try {
                openCamera();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {

        }
    };

    private void openCamera() throws CameraAccessException {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        cameraId = manager.getCameraIdList()[0];
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        assert map != null;
        imageDimensions = map.getOutputSizes(SurfaceTexture.class)[0];
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_CAMERA_PERMISSION);
            return;
        }
        manager.openCamera(cameraId, stateCallback, null);
    }

    private void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimensions.getWidth(), imageDimensions.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (cameraDevice == null)
                        return;
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Changed", Toast.LENGTH_SHORT).show();

                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void updatePreview() {
        if (cameraDevice == null)
            Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show();
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (textureView.isAvailable()) {
            try {
                openCamera();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        } else
            textureView.setSurfaceTextureListener(textureListener);
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void takePicture() {
        if (cameraId == null)
            return;
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Size[] jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
            int width = 640;
            int height = 480;
            if (jpegSizes != null && jpegSizes.length > 0) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }
            final ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
            List<Surface> outputSurface = new ArrayList<>(2);
            outputSurface.add(reader.getSurface());
            outputSurface.add(new Surface(textureView.getSurfaceTexture()));

            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(reader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATION.get(rotation));

            if (!Environment.getExternalStorageState().equals(
                    Environment.MEDIA_MOUNTED)) {
                Toast.makeText(MainActivity.this, "SD-card not found", Toast.LENGTH_SHORT).show();
                return;
            }

            file = new File(applicationStoragePath, NEW_FILENAME_SD() + imageFormat);

            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader imageReader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        save(bytes);
                        // update view only after file saved
                        onImageCapturedAndSavedToFiles();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }

                private void save(byte[] bytes) throws IOException {
                    OutputStream outputStream = null;
                    try {
                        outputStream = new FileOutputStream(file);
                        outputStream.write(bytes);
                    } finally {
                        if (outputStream != null) {
                            outputStream.close();
                        }
                    }

                }
            };
            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);
            final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    createCameraPreview();
                }
            };

            cameraDevice.createCaptureSession(outputSurface, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    try {
                        cameraCaptureSession.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //работа с папками и файлами:
    //создать и очистить папку,
    //определить имя следующего файла с проверкой существования

    private void createApplicationDirectory() {
        File imageDirectory = new File(applicationStoragePath);
        if (!imageDirectory.exists()) {
            if (!imageDirectory.mkdir()) {
                throw new RuntimeException("Can't create application storage folder");
            }
        }
    }

    private void clearApplicationDirectory() {
        File imageDirectory = new File(applicationStoragePath);
        if (imageDirectory.exists()) {
            for (File imagesFilePath : Objects.requireNonNull(new File(applicationStoragePath).listFiles()))
                if (imagesFilePath.isFile()) imagesFilePath.delete();
        } else {
            createApplicationDirectory();
        }
        Toast.makeText(MainActivity.this, "Directory cleared", Toast.LENGTH_SHORT).show();
        load();
    }

    private int NEW_FILENAME_SD() {
        boolean check = true;
        int lastNumber = 0;
        for (int i = 1; check; i++) {
            if (existQuestionSDFileName(i)) {
                check = true;
            } else {
                check = false;
                lastNumber = i;
            }
        }
        return lastNumber;
    }

    private boolean existQuestionSDFileName(int lastNumber) {
        File file = new File(applicationStoragePath, lastNumber + imageFormat);
        return file.exists();
    }

    //разрешения

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "You have to accept permission", Toast.LENGTH_SHORT).show();
                finish();
            }
        }

        if (requestCode == 1000) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                load();
            } else {
                Toast.makeText(this, "Permission not grained!", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}