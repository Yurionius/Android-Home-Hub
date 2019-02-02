package com.tunjid.rcswitchcontrol.fragments;


import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.tunjid.androidbootstrap.communications.nsd.NsdHelper;
import com.tunjid.rcswitchcontrol.R;
import com.tunjid.rcswitchcontrol.abstractclasses.BaseFragment;
import com.tunjid.rcswitchcontrol.adapters.NSDAdapter;
import com.tunjid.rcswitchcontrol.services.ClientNsdService;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static androidx.recyclerview.widget.DividerItemDecoration.VERTICAL;

/**
 * A {@link androidx.fragment.app.Fragment} listing supported NSD servers
 */
public class NsdScanFragment extends BaseFragment
        implements
        NSDAdapter.ServiceClickedListener {

    private static final long SCAN_PERIOD = 10000;    // Stops scanning after 10 seconds.

    private boolean isScanning;

    private RecyclerView recyclerView;

    private NsdHelper nsdHelper;

    private List<NsdServiceInfo> services = new ArrayList<>();

    public NsdScanFragment() {
        // Required empty public constructor
    }

    public static NsdScanFragment newInstance() {
        NsdScanFragment fragment = new NsdScanFragment();
        Bundle bundle = new Bundle();

        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        nsdHelper = NsdHelper.getBuilder(getContext())
                .setServiceFoundConsumer(this::onServiceFound)
                .setResolveSuccessConsumer(this::onServiceResolved)
                .setResolveErrorConsumer(this::onServiceResolutionFailed)
                .build();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_nsd_scan, container, false);
        Context context = root.getContext();

        recyclerView = root.findViewById(R.id.list);
        recyclerView.setAdapter(new NSDAdapter(this, services));
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.addItemDecoration(new DividerItemDecoration(context, VERTICAL));

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        scanDevices(true);
        getAdapter().notifyDataSetChanged();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        recyclerView = null;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_nsd_scan, menu);

        menu.findItem(R.id.menu_stop).setVisible(isScanning);
        menu.findItem(R.id.menu_scan).setVisible(!isScanning);

        if (!isScanning) {
            menu.findItem(R.id.menu_refresh).setVisible(false);
        }
        else {
            menu.findItem(R.id.menu_refresh).setVisible(true);
            menu.findItem(R.id.menu_refresh).setActionView(R.layout.actionbar_indeterminate_progress);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_scan:
                services.clear();
                getAdapter().notifyDataSetChanged();
                scanDevices(true);
                return true;
            case R.id.menu_stop:
                scanDevices(false);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        nsdHelper.tearDown();
    }

    @Override
    public void onServiceClicked(NsdServiceInfo serviceInfo) {
        Intent intent = new Intent(getContext(), ClientNsdService.class);
        intent.putExtra(ClientNsdService.NSD_SERVICE_INFO_KEY, serviceInfo);
        requireContext().startService(intent);

        showFragment(ClientNsdFragment.newInstance());
    }

    @Override
    public boolean isSelf(NsdServiceInfo serviceInfo) {
        return false;
    }

    private void onServiceFound(NsdServiceInfo service) {
        nsdHelper.resolveService(service);
    }

    private void onServiceResolved(NsdServiceInfo service) {
        if (!services.contains(service)) services.add(service);

        // Runs on a different thread, post here
        if (recyclerView != null) recyclerView.post(getAdapter()::notifyDataSetChanged);
    }

    private void onServiceResolutionFailed(NsdServiceInfo service, Integer reason) {
        if (reason == NsdManager.FAILURE_ALREADY_ACTIVE) nsdHelper.resolveService(service);
    }

    private void scanDevices(boolean enable) {
        isScanning = enable;

        if (enable) nsdHelper.discoverServices();
        else nsdHelper.stopServiceDiscovery();

        requireActivity().invalidateOptionsMenu();

        // Stops  after a pre-defined scan period.
        if (enable) recyclerView.postDelayed(() -> {
            isScanning = false;
            nsdHelper.stopServiceDiscovery();
            if (isVisible()) requireActivity().invalidateOptionsMenu();
        }, SCAN_PERIOD);
    }

    private RecyclerView.Adapter getAdapter() {
        return recyclerView.getAdapter();
    }
}
