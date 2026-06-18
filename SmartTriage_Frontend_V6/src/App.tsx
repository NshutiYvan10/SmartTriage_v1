import { useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useNavigate, useLocation } from 'react-router-dom';
import { Sidebar } from './components/Sidebar';
import { RoleGuard } from './components/RoleGuard';
import { DirectResusFAB } from './components/DirectResusFAB';
import { CriticalAlertNotifier } from './components/CriticalAlertNotifier';
import { Dashboard } from './modules/dashboard/Dashboard';
import { EntryRegistration } from './modules/entry/EntryRegistration';
import { PediatricTriageForm } from './modules/triage/PediatricTriageForm';
import { AdultTriageForm } from './modules/triage/AdultTriageForm';
import { TriageQueue } from './modules/triage/TriageQueue';
import { VitalMonitoring } from './modules/vitals/VitalMonitoring';
import { ConstantMonitoring } from './modules/monitoring/ConstantMonitoring';
import { AlertsView } from './modules/alerts/AlertsView';
import { ReportsView } from './modules/reports/ReportsView';
import { SettingsView } from './modules/settings/SettingsView';
import { NotificationsPage } from './modules/notifications/NotificationsPage';
import { ProfilePage } from './modules/profile/ProfilePage';
import { PatientsList } from './modules/patients/PatientsList';
import { PatientDetailView } from './modules/patient/PatientDetailView';
import { AuditTrail } from './modules/audit/AuditTrail';
import { LandingPage } from './pages/LandingPage';
import { LoginPage } from './pages/LoginPage';
import { ActivateAccountPage } from './pages/ActivateAccountPage';
import { VisitDetailPage } from './modules/visit/VisitDetailPage';
import { IoTDeviceManagement } from './modules/iot/IoTDeviceManagement';
import { HospitalManagement } from './modules/admin/HospitalManagement';
import { UserManagement } from './modules/admin/UserManagement';
import { ShiftPlannerPage } from './modules/shift/ShiftPlannerPage';
import { ShiftCalendarPage } from './modules/shift/ShiftCalendarPage';
import { MySchedulePage } from './modules/shift/MySchedulePage';
import { SwapApprovalsPage } from './modules/shift/SwapApprovalsPage';
import { LeaveApprovalsPage } from './modules/shift/LeaveApprovalsPage';
import { DelegationsPage } from './modules/shift/DelegationsPage';
import { PendingTransfersDashboard } from './modules/zone/PendingTransfersDashboard';
import { DoctorWorkspace } from './modules/doctor/DoctorWorkspace';
import { BedGridView } from './modules/beds/BedGridView';
import { BedManagement } from './modules/beds/BedManagement';
import { useTheme } from './hooks/useTheme';
import { useDataInit } from './hooks/useDataInit';
import { useWebSocket } from './hooks/useWebSocket';
import { useMyShift } from './hooks/useMyShift';
import { useAuthStore } from './store/authStore';
import { SepsisDashboard } from './modules/sepsis/SepsisDashboard';
import { FastTrackDashboard } from './modules/fasttrack/FastTrackDashboard';
import { HypoglycemiaView } from './modules/hypoglycemia/HypoglycemiaView';
import { IsolationDashboard } from './modules/isolation/IsolationDashboard';
import { ClinicalPathwaysView } from './modules/pathway/ClinicalPathwaysView';
import { MedicationSafetyView } from './modules/medsafety/MedicationSafetyView';
import { MedicationSafetyOverridesView } from './modules/medsafety/MedicationSafetyOverridesView';
import { IcuEscalationView } from './modules/icu/IcuEscalationView';
import { ClinicalDocumentation } from './modules/documentation/ClinicalDocumentation';
import { HandoverView } from './modules/handover/HandoverView';
import { SafetyIncidentView } from './modules/safety/SafetyIncidentView';
import { MohReportView } from './modules/mohreport/MohReportView';
import { GovernanceAdmin } from './modules/governance/GovernanceAdmin';
import { QualityDashboard } from './modules/quality/QualityDashboard';
import { SurgePredictionView } from './modules/prediction/SurgePredictionView';
import { LabOrdersView } from './modules/lab/LabOrdersView';
import { NurseMedicationQueue } from './modules/medication/NurseMedicationQueue';
import { MedicationBoard } from './modules/medication/MedicationBoard';
import { DoctorInvestigationsView } from './modules/investigations/DoctorInvestigationsView';
import { ParamedicDashboard } from './modules/ems/ParamedicDashboard';
import { ErrorBoundary } from './components/ErrorBoundary';

function AppContent() {
  const [currentView, setCurrentView] = useState('dashboard');
  const [sidebarWidth, setSidebarWidth] = useState(72); // Default collapsed width
  const navigate = useNavigate();
  const location = useLocation();
  const { isDark, pageBg } = useTheme();
  const user = useAuthStore((s) => s.user);

  // Resolve user's current zone assignment from shift data
  const { zone: myZone } = useMyShift();

  // Hydrate stores from API when authenticated
  useDataInit();
  // Connect WebSocket for real-time updates (includes zone subscription)
  useWebSocket(myZone);

  // Landing page is displayed full-screen without sidebar
  const isLanding = location.pathname === '/';
  const isLogin = location.pathname === '/login';
  const isActivate = location.pathname === '/activate';

  const handleNavigate = (view: string) => {
    setCurrentView(view);
    navigate(`/${view}`);
  };

  const handleSidebarExpand = () => {
    setSidebarWidth(272);
  };

  const handleSidebarCollapse = () => {
    setSidebarWidth(72);
  };

  // Landing page — full screen, no sidebar
  if (isLanding) {
    return (
      <Routes>
        <Route path="/" element={<LandingPage />} />
      </Routes>
    );
  }

  // Login page — full screen, no sidebar
  if (isLogin) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
      </Routes>
    );
  }

  // Activate account page — full screen, no sidebar (public)
  if (isActivate) {
    return (
      <Routes>
        <Route path="/activate" element={<ActivateAccountPage />} />
      </Routes>
    );
  }

  // ── Authentication gate: redirect to /login if not authenticated ──
  if (!user) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div
      className="flex h-screen bg-mesh transition-colors duration-500"
      style={{ background: pageBg }}
    >
      <Sidebar
        currentView={currentView}
        onNavigate={handleNavigate}
        onCollapse={handleSidebarCollapse}
        onExpand={handleSidebarExpand}
        isExpanded={sidebarWidth === 272}
      />

      <main
        className="flex-1 min-w-0 overflow-y-auto transition-all duration-500 ease-out relative"
        style={{ marginLeft: `${sidebarWidth + 32}px` }}
      >
        <div className="relative z-10 animate-fade-in">
          {/* ── Clinical-safety failsafe ──
              Wrap every route in an ErrorBoundary so a thrown error
              during render (e.g. a transient race between auth and
              store hydration) shows a recoverable fallback instead
              of unmounting the whole tree to a blank gradient page —
              the silent failure mode behind every "had to reload to
              see the dashboard" report. */}
          <ErrorBoundary routeLabel={location.pathname.replace('/', '') || 'page'}>
          <Routes>
            <Route path="/dashboard" element={<Dashboard />} />
            <Route path="/entry" element={<RoleGuard page="entry"><EntryRegistration /></RoleGuard>} />
            <Route path="/patients" element={<RoleGuard page="patients"><PatientsList /></RoleGuard>} />
            <Route path="/patients/:patientId" element={<RoleGuard page="patients"><PatientDetailView /></RoleGuard>} />
            {/* RBAC fix — triage routes require an active TRIAGE_NURSE shift
                function. Charge Nurse authority (designation or shift-lead
                badge) bypasses this gate as the documented override path.
                Doctors / Zone Nurses / admins are denied. */}
            <Route path="/triage" element={<RoleGuard page="triage" requiresShiftFunction={['TRIAGE_NURSE']}><TriageQueue /></RoleGuard>} />
            <Route path="/pediatric-triage/new" element={<RoleGuard page="triage" requiresShiftFunction={['TRIAGE_NURSE']}><PediatricTriageForm /></RoleGuard>} />
            <Route path="/pediatric-triage/:patientId" element={<RoleGuard page="triage" requiresShiftFunction={['TRIAGE_NURSE']}><PediatricTriageForm /></RoleGuard>} />
            <Route path="/adult-triage/new" element={<RoleGuard page="triage" requiresShiftFunction={['TRIAGE_NURSE']}><AdultTriageForm /></RoleGuard>} />
            <Route path="/adult-triage/:patientId" element={<RoleGuard page="triage" requiresShiftFunction={['TRIAGE_NURSE']}><AdultTriageForm /></RoleGuard>} />
            <Route path="/visit/:visitId" element={<RoleGuard page="triage"><VisitDetailPage /></RoleGuard>} />
            <Route path="/doctor-workspace" element={<RoleGuard page="triage"><DoctorWorkspace /></RoleGuard>} />
            <Route path="/vitals/:patientId" element={<RoleGuard page="monitoring"><VitalMonitoring /></RoleGuard>} />
            <Route path="/monitoring" element={<RoleGuard page="monitoring"><ConstantMonitoring /></RoleGuard>} />
            <Route path="/monitoring/:patientId" element={<RoleGuard page="monitoring"><VitalMonitoring /></RoleGuard>} />
            <Route path="/alerts" element={<RoleGuard page="alerts"><AlertsView /></RoleGuard>} />
            {/* Legacy route — the stale REST-only "Alert Center" page was removed; the live
                AlertsView at /alerts is now the single canonical Alert Center. */}
            <Route path="/alert-dashboard" element={<Navigate to="/alerts" replace />} />
            <Route path="/iot-devices" element={<RoleGuard page="iot-devices"><IoTDeviceManagement /></RoleGuard>} />
            <Route path="/lab" element={<RoleGuard page="lab"><LabOrdersView /></RoleGuard>} />
            <Route path="/med-queue" element={<RoleGuard page="med-queue"><NurseMedicationQueue /></RoleGuard>} />
            <Route path="/med-board" element={<RoleGuard page="med-board"><MedicationBoard /></RoleGuard>} />
            <Route path="/investigations" element={<RoleGuard page="investigations"><DoctorInvestigationsView /></RoleGuard>} />
            <Route path="/ems" element={<RoleGuard page="ems"><ParamedicDashboard /></RoleGuard>} />
            <Route path="/beds" element={<RoleGuard page="beds"><BedGridView /></RoleGuard>} />
            <Route path="/admin/hospitals" element={<RoleGuard page="admin-hospitals"><HospitalManagement /></RoleGuard>} />
            <Route path="/admin/users" element={<RoleGuard page="admin-users"><UserManagement /></RoleGuard>} />
            <Route path="/admin/beds" element={<RoleGuard page="admin-beds"><BedManagement /></RoleGuard>} />
            {/* Today-only ShiftAssignment page is retired — calendar is now the
                single planning surface. Redirect any old links / bookmarks to
                the calendar landing on today. */}
            <Route path="/shift-assignment" element={<Navigate to="/shift-calendar" replace />} />
            <Route path="/zone-transfers" element={<PendingTransfersDashboard />} />
            <Route path="/shift-planner" element={<RoleGuard page="shift-planner" allowDesignations={['CHARGE_NURSE']}><ShiftPlannerPage /></RoleGuard>} />
            <Route path="/shift-calendar" element={<RoleGuard page="shift-calendar" allowDesignations={['CHARGE_NURSE']}><ShiftCalendarPage /></RoleGuard>} />
            <Route path="/swap-approvals" element={<RoleGuard page="swap-approvals" allowDesignations={['CHARGE_NURSE']}><SwapApprovalsPage /></RoleGuard>} />
            <Route path="/leave-approvals" element={<RoleGuard page="leave-approvals" allowDesignations={['CHARGE_NURSE']}><LeaveApprovalsPage /></RoleGuard>} />
            <Route path="/delegations" element={<RoleGuard page="delegations" allowDesignations={['CHARGE_NURSE']}><DelegationsPage /></RoleGuard>} />
            <Route path="/my-schedule" element={<RoleGuard page="my-schedule"><MySchedulePage /></RoleGuard>} />
            <Route path="/audit-trail" element={<RoleGuard page="audit-trail"><AuditTrail /></RoleGuard>} />
            <Route path="/reports" element={<RoleGuard page="reports"><ReportsView /></RoleGuard>} />
            <Route path="/notifications" element={<NotificationsPage />} />
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/settings" element={<RoleGuard page="settings"><SettingsView /></RoleGuard>} />
            {/* Clinical Tools */}
            <Route path="/sepsis" element={<RoleGuard page="sepsis"><SepsisDashboard /></RoleGuard>} />
            <Route path="/fast-track" element={<RoleGuard page="fast-track"><FastTrackDashboard /></RoleGuard>} />
            <Route path="/hypoglycemia" element={<RoleGuard page="hypoglycemia"><HypoglycemiaView /></RoleGuard>} />
            <Route path="/isolation" element={<RoleGuard page="isolation"><IsolationDashboard /></RoleGuard>} />
            <Route path="/pathways" element={<RoleGuard page="pathways"><ClinicalPathwaysView /></RoleGuard>} />
            <Route path="/med-safety" element={<RoleGuard page="med-safety"><MedicationSafetyView /></RoleGuard>} />
            {/* CHARGE_NURSE is a real Designation and grants floor-oversight access here;
                the previous SUPERVISOR/SAFETY_OFFICER values are not in the Designation enum
                (the safety-officer persona is the READ_ONLY role, which has the page) so they
                were dead no-ops. Backend canAuditSafetyOverrides mirrors this audience. */}
            <Route path="/med-safety/overrides" element={<RoleGuard page="med-safety-overrides" allowDesignations={['CHARGE_NURSE']}><MedicationSafetyOverridesView /></RoleGuard>} />
            <Route path="/icu" element={<RoleGuard page="icu"><IcuEscalationView /></RoleGuard>} />
            {/* Documentation & Handover */}
            <Route path="/documentation" element={<RoleGuard page="documentation"><ClinicalDocumentation /></RoleGuard>} />
            <Route path="/handover" element={<RoleGuard page="handover"><HandoverView /></RoleGuard>} />
            {/* Administration & Governance */}
            <Route path="/safety-incidents" element={<RoleGuard page="safety-incidents"><SafetyIncidentView /></RoleGuard>} />
            <Route path="/moh-reports" element={<RoleGuard page="moh-reports"><MohReportView /></RoleGuard>} />
            <Route path="/governance" element={<RoleGuard page="governance"><GovernanceAdmin /></RoleGuard>} />
            <Route path="/quality" element={<RoleGuard page="quality"><QualityDashboard /></RoleGuard>} />
            <Route path="/prediction" element={<RoleGuard page="prediction"><SurgePredictionView /></RoleGuard>} />
          </Routes>
          </ErrorBoundary>
        </div>

      </main>

      {/* ── Direct Resus floating action button (V44) ──
          Persistent on every authenticated page so a clinician can
          trigger Red-patient admission in one click from anywhere
          in the app. Self-gates: hides on /entry, on unauthenticated
          routes, and for non-clinical roles. */}
      <DirectResusFAB />

      {/* ── Global critical-alert notifier ──
          Beeps + flashes + toasts when a new CRITICAL alert lands in
          the store from the WebSocket. Self-quiets on the alert center
          pages where the user is already looking at alerts. */}
      <CriticalAlertNotifier />
    </div>
  );
}

function App() {
  return (
    <BrowserRouter>
      <AppContent />
    </BrowserRouter>
  );
}

export default App;
