package com.smartTriage.smartTriage_server.module.iot.service;

import com.smartTriage.smartTriage_server.common.enums.DeviceType;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.iot.dto.DeviceLatestVitalsResponse;
import com.smartTriage.smartTriage_server.module.iot.dto.DeviceTelemetryRequest;
import com.smartTriage.smartTriage_server.module.iot.dto.SelfRegisterMonitorRequest;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.repository.IoTDeviceRepository;
import com.smartTriage.smartTriage_server.module.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the paramedic self-registered monitor + device-keyed vitals
 * snapshot (V98). Authorization (canOperateDevice) is covered in ClinicalAuthzTest.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceServiceParamedicTest {

    @Mock private IoTDeviceRepository deviceRepository;
    @Mock private HospitalService hospitalService;
    @Mock private UserRepository userRepository;

    @InjectMocks private DeviceService deviceService;

    @Test
    void selfRegister_forcesTypeAndOwnership_takesHospitalFromCaller() {
        UUID caller = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        when(userRepository.findHospitalIdByUserId(caller)).thenReturn(Optional.of(hospitalId));
        when(hospitalService.findHospitalOrThrow(hospitalId)).thenReturn(hospital);
        when(deviceRepository.findBySerialNumberAndIsActiveTrue("SN-99")).thenReturn(Optional.empty());
        when(deviceRepository.save(any(IoTDevice.class))).thenAnswer(i -> i.getArgument(0));

        // The self-register DTO carries only serial + name; type + hospital are forced server-side.
        SelfRegisterMonitorRequest req = SelfRegisterMonitorRequest.builder()
                .serialNumber("SN-99")
                .deviceName("Crew 7 monitor")
                .build();

        var resp = deviceService.selfRegisterParamedicMonitor(req, caller);

        ArgumentCaptor<IoTDevice> saved = ArgumentCaptor.forClass(IoTDevice.class);
        verify(deviceRepository).save(saved.capture());
        IoTDevice d = saved.getValue();
        assertThat(d.getDeviceType()).isEqualTo(DeviceType.PARAMEDIC_MONITOR);   // forced
        assertThat(d.getRegisteredByUserId()).isEqualTo(caller);                 // owned by caller
        assertThat(d.getHospital().getId()).isEqualTo(hospitalId);              // caller's hospital
        assertThat(resp.getApiKey()).isNotBlank();                              // pairing key returned once
    }

    @Test
    void recordDeviceTelemetry_updatesLatestSnapshot_onlyNonNullFields() {
        IoTDevice device = IoTDevice.builder().serialNumber("SN-1").deviceName("m").apiKey("k").build();
        device.setLastSpo2(90);   // pre-existing value that a partial payload must NOT wipe
        when(deviceRepository.findByApiKeyAndIsActiveTrue("k")).thenReturn(Optional.of(device));
        when(deviceRepository.save(any(IoTDevice.class))).thenAnswer(i -> i.getArgument(0));

        deviceService.recordDeviceTelemetry("k", DeviceTelemetryRequest.builder()
                .heartRate(82).systolicBp(120).diastolicBp(78).temperature(37.1).build());

        assertThat(device.getLastHeartRate()).isEqualTo(82);
        assertThat(device.getLastSystolicBp()).isEqualTo(120);
        assertThat(device.getLastTemperature()).isEqualTo(37.1);
        assertThat(device.getLastSpo2()).isEqualTo(90);          // untouched (payload omitted it)
        assertThat(device.getLastVitalsAt()).isNotNull();
        verify(deviceRepository).save(device);
    }

    @Test
    void getLatestVitals_returnsSnapshot_withAge_whenReadingExists() {
        UUID id = UUID.randomUUID();
        IoTDevice device = IoTDevice.builder().serialNumber("SN-1").deviceName("Crew 7 monitor").apiKey("k").build();
        device.setId(id);
        device.setLastHeartRate(88);
        device.setLastSpo2(96);
        device.setLastVitalsAt(Instant.now().minusSeconds(10));
        when(deviceRepository.findByIdAndIsActiveTrue(id)).thenReturn(Optional.of(device));

        DeviceLatestVitalsResponse resp = deviceService.getLatestVitals(id);

        assertThat(resp.isHasReading()).isTrue();
        assertThat(resp.getHeartRate()).isEqualTo(88);
        assertThat(resp.getSpo2()).isEqualTo(96);
        assertThat(resp.getAgeSeconds()).isNotNull();
        assertThat(resp.getDeviceName()).isEqualTo("Crew 7 monitor");
    }

    @Test
    void getLatestVitals_flagsNoReading_whenDeviceNeverReported() {
        UUID id = UUID.randomUUID();
        IoTDevice device = IoTDevice.builder().serialNumber("SN-2").deviceName("m").apiKey("k").build();
        device.setId(id);   // no lastVitalsAt
        when(deviceRepository.findByIdAndIsActiveTrue(id)).thenReturn(Optional.of(device));

        DeviceLatestVitalsResponse resp = deviceService.getLatestVitals(id);

        assertThat(resp.isHasReading()).isFalse();
        assertThat(resp.getAgeSeconds()).isNull();
    }
}
