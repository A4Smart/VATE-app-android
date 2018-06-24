package com.application.handing.vateapp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.estimote.coresdk.common.requirements.SystemRequirementsChecker;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static android.content.Context.BLUETOOTH_SERVICE;

public class HomeFragment extends Fragment implements BackPressedFragment {



    //array di grandezza MEMORIA_FIFO con ultimi majo/mino letti
    //beacon deve essere presente almeno VALORE_MINIMO volte per essere effettivamente il più vicino
    private final static int MEMORIA_FIFO = 4;
    private final static int VALORE_MINIMO = 3;

    private final static String INDIRIZZO_WEB = "https://vateapp.eu/";

    //Beacon da usare per App Vate
    //array di array -> il primo elemento è il major, gli altri tutti i minor associati a quel major
    private final int [][] beaconVate = { {1, 1,2,3}, {100, 1}, {200, 1,2,3,4,5}, //BevilacquaFragment
            {10000, 1,2,4,5,8,10,11,13,14,15,18,19,20,21,24,28} }; //Negozi piazza
    //{5000, 1,2,3,4}}; //Ufficio
    int [] majorInterni = {1, 100, 200};
    int [] majorEsterni = {10000};

    private ImageView immagineSfondo;
    private ProgressBar webProgress;
    private WebView webVista;

    private int lastMajor, lastMinor;//ultimi valori letti majo/mino del beacon più vicino
    private ArrayList prevMajor, prevMinor;//array con ultimi major e minor per scegliere NearestBeacon
    private boolean isStarted;//vero se pulsante play è stato schiacciato, deve ritornare falso quando esco dall'app (oppure no, da vedere)
    private String lastUrl;



    //GESTIONE BLUETOOTH
    final private static int BT_REQUEST_ID = 1;
    private final static BeaconsAdapter mAdapter = new BeaconsAdapter();//dichiarazione oggetto della classe BeaconsAdapter
    final private Handler mHandler = new Handler();//handler per gestire messaggi e runnable
    private BluetoothAdapter mBtAdapter = null;//BtAdapter to communicate with bluetooth
    //Callback
    private BluetoothAdapter.LeScanCallback mLeOldCallback = null;
    private ScanCallback mLeNewCallback = null;

    final private static long VALIDATION_PERIOD = 3000;
    //END GESTIONE BLUETOOTH

    public static HomeFragment newInstance () {
        return new HomeFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.frag_main, container, false);
        getActivity().setTitle(R.string.app_name);

        initializeCallback();

        //beacVicino = (TextView) findViewById(R.id.textView);
        //DICHIARAZIONE CONTENUTI LAYOUT
        immagineSfondo = v.findViewById(R.id.imgSfondo);
        webProgress = v.findViewById(R.id.progressWebView);
        webVista = v.findViewById(R.id.vistaWeb);

        prevMajor = new ArrayList();
        prevMinor = new ArrayList();
        isStarted = false;
        lastMajor = lastMinor = 0;
        lastUrl = "vuoto";

        //GESTIONE WEBVIEW, PROGRESS BAR durante caricamento pagina web
        webVista.setWebChromeClient(new WebChromeClient() {
            public void onProgressChanged(WebView view, int progress) {
                if (isStarted && progress < 75) {
                    if (webProgress.getVisibility() != View.VISIBLE)
                        webProgress.setVisibility(View.VISIBLE);
                    webProgress.setProgress(progress);
                } else
                    webProgress.setVisibility(View.INVISIBLE);
            }
        });
        webVista.setWebViewClient(new WebViewClient());
        webVista.getSettings().setJavaScriptEnabled(true);

        return v;
    }



    @Override
    public void onResume() {
        super.onResume();

        SystemRequirementsChecker.checkWithDefaultDialogs(getActivity());
        startScanning();
        startValidating();
    }

    @Override
    public void onPause() {
        super.onPause();

        stopScanning();
        stopValidating();

        turnWebOff();//quando si esce dall'activity, ritorna allo stato iniziale e formatta gli array con ultimi major/minor
        isStarted = false;
        lastMajor = lastMinor = 0;
        mAdapter.nearestMaio = 0;
        mAdapter.nearestMino = 0;
        prevMajor.clear();
        prevMinor.clear();
    }


    public void onBackPressed() {//decidere se lasciare la possibilità di navigare avanti/indietro oppure no
        //ho tolto la possiblità di navigare indietro
        //se si schiaccia la doppia freccia, si torna alla schermata iniziale

        /*if(webVista.canGoBack()) {
            webVista.goBack();
        } else {
            //super.onBackPressed();
            turnWebOff();
            isStarted = false;
            fabStart.setVisibility(View.VISIBLE);
        }*/
        turnWebOff();
        stopScanning();
        stopValidating();
        isStarted = false;
    }

    //Funzione che controlla se il beacon è uno di quelli usati per Vate
    private boolean isBeaconVate(int tempMajor, int tempMinor){
        for (int[] aBeaconVate : beaconVate) {
            if (tempMajor == aBeaconVate[0]) {//tutti i major sono il primo elemento dei vari array
                //minors deve partire da 1 perchè 0 è il major
                for (int minors = 1; minors < aBeaconVate.length; minors++) {
                    if (tempMinor == aBeaconVate[minors])
                        return true;
                }
                return false;
            }
        }
        return false;
    }


    //GESTIONE VISUALIZZAZIONE/SPEGNIMENTO LAYOUT WEB
    private void turnWebOn(int major, int minor){
        if(immagineSfondo.getVisibility() == View.VISIBLE)
            immagineSfondo.setVisibility(View.INVISIBLE);
        if(webVista.getVisibility() == View.INVISIBLE)
            webVista.setVisibility(View.VISIBLE);
        /*if(!webVista.getUrl().equals(INDIRIZZO_WEB + major + "_" + minor + "/")*/
        if(!lastUrl.equals(INDIRIZZO_WEB + major + "_" + minor + "/")) {
            lastUrl = INDIRIZZO_WEB + major + "_" + minor + "/";
            //webProgress.setVisibility(View.VISIBLE);//vedere se nel telefono vecchio parte prima
            webVista.loadUrl(INDIRIZZO_WEB + major + "_" + minor + "/");
        }
    }

    private void turnWebOff(){
        webVista.setVisibility(View.INVISIBLE);
        immagineSfondo.setVisibility(View.VISIBLE);
    }
    //END GESTIONE LAYOUT

    //BLUETOOTH CONTROL
    //control if Bt is enabled, create an instance of a BluetoothAdapter, into startScanning
    private boolean isBluetoothAvailableAndEnabled() {
        BluetoothManager btManager = (BluetoothManager) getActivity().getSystemService(BLUETOOTH_SERVICE);
        mBtAdapter = btManager.getAdapter();

        return mBtAdapter != null && mBtAdapter.isEnabled();
    }

    //new activity to enable Bluetooth hardware, into startScanning
    private void requestForBluetooth() {
        Intent request = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(request, BT_REQUEST_ID);//ritorna onActivityResult quando viene creata l'activity
    }


    //CALLBACK CREATION
    //crea gli oggetti di callback e relative funzioni, utilizzate durante startScanning
    private void initializeCallback() {
        mLeNewCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, final ScanResult result) {
                if (result.getScanRecord() == null ||
                        result.getScanRecord().getBytes() == null) {
                    return;
                }
                handleNewBeaconDiscovered(
                        result.getDevice(),
                        result.getRssi(),
                        result.getScanRecord().getBytes());
            }

            @Override
            //return information about more than one device
            public void onBatchScanResults(List<ScanResult> results) {
                for (final ScanResult result : results) {
                    onScanResult(0, result);
                }
            }
        };

    }
    //END CALLBACK CREATION

    //INIZIO SCANSIONE
    private void startScanning() {//scan result riportati come callback
        //isLocationAvailableAndEnabled();
        Log.d("STARTSCANNING", "StartScanning is called");
        System.out.print("StartScanning is called");
        if (!isBluetoothAvailableAndEnabled()) {
            //requestForBluetooth();
        } else {
            BluetoothLeScanner scanner = mBtAdapter.getBluetoothLeScanner();//return a BtLeScanner instance
            if (scanner != null) {
                //defining settings for the scan
                ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)//SCAN_MODE_LOW_LATENCY
                        // LOW_LATENCY, LOW_ENERGY,BALANCE ...vari tipi di scan possibili
                        //.setReportDelay()//milliseconds
                        .build();
                scanner.startScan(null, settings, mLeNewCallback);//List<ScanFilter> filters, ScanSettings settings, ScanCallback callback
            }
        }
    }
    //STOP SCANSIONE
    private void stopScanning() {
        BluetoothLeScanner scanner = mBtAdapter.getBluetoothLeScanner();
        if (scanner != null) {
            scanner.stopScan(mLeNewCallback);
        }
    }

    //HANDLE NEW BEACON DISCOVERED
    // Funzione di risposta alla ricezione di ogni Handle da bluethoot LB
    //se nuovo Beacon, aggiungi alla lista
    //se vecchio Beacon, aggiorna i valori e medie
    private void handleNewBeaconDiscovered(final BluetoothDevice device,
                                           final int rssi,
                                           final byte[] advertisement) {
        final BeaconModel beaconToAdd;
        BeaconModel beacon = mAdapter.findBeaconWithId(device.getAddress());

        if (beacon == null) {//se nuovo beacon, aggiungi alla lista
            // new item
            beacon = new BeaconModel();
            beacon.dataEntry(device, rssi, advertisement);
            //controllo se effettivamente non era già presente
            //perchè a volte sbaglia
            //prima non si usava justFinded, da controllare
            if(!beacon.isEstimoteBeacon() || mAdapter.justFinded(beacon.major, beacon.minor)) return;
            if(!isBeaconVate(beacon.major, beacon.minor)) return;
            beaconToAdd = beacon;
        }
        else {//se vecchio Beacon, aggiorna i valori
            beaconToAdd = null;

            if(rssi < 0 && rssi >-150){  // rssi deve essere minore di zero e non troppo piccolo (>=0 non ok)
                beacon.istantRssi = rssi;
                beacon.rssi_list.add(rssi);//aggiungi valore rssi alla lista
                beacon.durate = new Date().getTime() - beacon.timestamp;
                beacon.timestamp = new Date().getTime();
                mAdapter.updating(beacon); // Aggiorno rssi, medie etc
            }
            //if(!isStarted) return;

            mAdapter.nearestBeac();
            int major = mAdapter.nearestMaio;
            int minor = mAdapter.nearestMino;
            fifo(major, minor);
            if(!isStarted) return;
            if ((lastMajor != major || lastMinor != minor) && isRealNearest(major,minor)) {
                String bau = "near: major " + String.valueOf(mAdapter.nearestMaio) + " minor " + String.valueOf(mAdapter.nearestMino);
                //beacVicino.setText(bau);
                turnWebOn(major, minor);
                lastMajor = major;
                lastMinor = minor;
            }
        }
        //controlla se è un nostro beacon
        //if(!beacon.isDemoBeacon()) return;

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (beaconToAdd != null) {
                    mAdapter.addNewBeacon(beaconToAdd);
                } else {
                    // just notify about changes in underlying data
                    /*mAdapter.nearestBeac();
                    int Maio = mAdapter.nearestMaio;
                    int Mino = mAdapter.nearestMino;
                    fifo(Maio, Mino);
                    //beacVicino.setText("near: major " + mAdapter.nearestMaio + " minor " + mAdapter.nearestMino);
                    if ((lastMajor != Maio || lastMinor != Mino) && isRealNearest(Maio,Mino)) {
                        turnWebOn(Maio, Mino);
                        lastMajor = Maio;
                        lastMinor = Mino;
                    }*/
                }
            }
        });
    }
    //END UPDATING BEACON MEASURES

    //BEACON VALIDATION
    //se dopo un tot non sento un dispositivo, toglilo dalla lista
    private void startValidating() {
        mHandler.postDelayed(periodicValidationTask, VALIDATION_PERIOD);
    }

    private void stopValidating() {
        mHandler.removeCallbacks(periodicValidationTask);
    }

    private final Runnable periodicValidationTask = new Runnable() {
        @Override
        public void run() {
            /*if (mAdapter.validateAllBeacons()) {
                mAdapter.notifyDataSetChanged();
            }*/
            mAdapter.validateAllBeacons();

            //ricomincia il giro
            startValidating();
        }
    };
    //END BEACON VALIDATION

    //FIFO ULTIMI VALORI MAJOR/MINOR
    private void fifo(int nowMajor, int nowMinor){
        if(prevMajor.size()>MEMORIA_FIFO && prevMinor.size()>MEMORIA_FIFO){
            prevMajor.remove(0);
            prevMinor.remove(0);
        }
        prevMajor.add(nowMajor);
        prevMinor.add(nowMinor);
    }
    //VERIFICA BEACON PIU' VICINO
    private boolean isRealNearest(int nowMaio, int nowMino){
        if(!isBeaconVate(nowMaio,nowMino)) return false;
        int counter = 0;

        for(int i = 0; i< prevMajor.size(); i++){
            if(prevMajor.get(i).equals(nowMaio) && prevMinor.get(i).equals(nowMino))
                counter ++;
        }

        return counter >= VALORE_MINIMO;
    }

}