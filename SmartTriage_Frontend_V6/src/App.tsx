import { useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useNavigate, useLocation, useParams } from 'react-router-dom';
import { Sidebar } from './components/Sidebar';
import { RoleGuard } from './components/RoleGuard';
import { DirectResusFAB } from './components/DirectResusFAB';
import { Dashboard } from './modules/dashboard/Dashboard';
import { EntryRegistration } from './modules/entry/EntryRegistration';
import { PediatricTriageForm } from './modules/triage/PediatricTriageForm';
import { AdultTriageForm } from './modules/triage/AdultTriageForm';
import { TriageQueue } from './modules/triage/TriageQueue';
// VitalMonitoring (the standalone /vitals/:patientId page) has been folded
// into VisitDetailPage's "Monitor" tab so vitals + assessment + monitoring
// live on a single chart. /vitals/:patientId and /monitoring/:patientId now
// redirect to /visit/:patientId?tab=monitor so old deep links keep working.
function LegacyVitalsRedirect() {
  const { patientId } = useParams<{ patientId: string }>();
  // The /vitals/:patientId route was historically called with a visitId
  // (see VitalMonitoring's "patientId IS the visitId" comment), so the
  // route param maps directly onto /visit/:visitId.
  return <Navigate to={`/visit/${patientId}?tab=monitor`} replace />;
}
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
import { AlertDashboard } from './modules/alerts/AlertDashboard';
import { IoTDeviceManagement } from './modules/iot/IoTDeviceManagement';
import { HospitalManagement } from './modules/admin/HospitalManagement';
import { UserManagement } from './modules/admin/UserManagement';
import { ShiftAssignment } from './modules/shift/ShiftAssignment';
import { ShiftPlannerPage } from './modules/shift/ShiftPlannerPage';
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
import { IcuEscalationView } from './modules/icu/IcuEscalationView';
import { ReferralManagement } from './modules/referral/ReferralManagement';
import { ClinicalDocumentation } from './modules/documentation/ClinicalDocumentation';
import { HandoverView } from './modules/handover/HandoverView';
import { SafetyIncidentView } from './modules/safety/SafetyIncidentView';
import { MohReportView } from './modules/mohreport/MohReportView';
import { GovernanceAdmin } from './modules/governance/GovernanceAdmin';
import { QualityDashboard } from './modules/quality/QualityDashboard';
import { SurgePredictionView } from './modules/prediction/SurgePredictionView';

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
          <Routes>
            <Route path="/dashboard" element={<Dashboard />} />
            <Route path="/entry" element={<RoleGuard page="entry"><EntryRegistration /></RoleGuard>} />
            <Route path="/patients" element={<RoleGuard page="patients"><PatientsList /></RoleGuard>} />
            <Route path="/patients/:patientId" element={<RoleGuard page="patients"><PatientDetailView /></RoleGuard>} />
            <Route path="/triage" element={<RoleGuard page="triage"><TriageQueue /></RoleGuard>} />
            <Route path="/pediatric-triage/new" element={<RoleGuard page="triage"><PediatricTriageForm /></RoleGuard>} />
            <Route path="/pediatric-triage/:patientId" element={<RoleGuard page="triage"><PediatricTriageForm /></RoleGuard>} />
            <Route path="/adult-triage/new" element={<RoleGuard page="triage"><AdultTriageForm /></RoleGuard>} />
            <Route path="/adult-triage/:patientId" element={<RoleGuard page="triage"><AdultTriageForm /></RoleGuard>} />
            <Route path="/visit/:visitId" element={<RoleGuard page="triage"><VisitDetailPage /></RoleGuard>} />
            <Route path="/doctor-workspace" element={<RoleGuard page="triage"><DoctorWorkspace /></RoleGuard>} />
            {/* Legacy routes — both pointed at the standalone VitalMonitoring page.
                That page has been folded into VisitDetailPage's Monitor tab so a
                doctor never has to flip between /visit and /vitals for the same
                patient. RoleGuard intentionally omitted here: every role that
                could access /vitals/:id (DOCTOR / NURSE / TRIAGE_NURSE) also has
                access to /visit/:id, so the redirect can never lock anyone out. */}
            <Route path="/vitals/:patientId" element={<LegacyVitalsRedirect />} />
            <Route path="/monitoring" element={<RoleGuard page="monitoring"><ConstantMonitoring /></RoleGuard>} />
            <Route path="/monitoring/:patientId" element={<LegacyVitalsRedirect />} />
            <Route path="/alerts" element={<RoleGuard page="alerts"><AlertsView /></RoleGuard>} />
            <Route path="/alert-dashboard" element={<RoleGuard page="alerts"><AlertDashboard /></RoleGuard>} />
            <Route path="/iot-devices" element={<RoleGuard page="iot-devices"><IoTDeviceManagement /></RoleGuard>} />
            <Route path="/beds" element={<RoleGuard page="beds"><BedGridView /></RoleGuard>} />
            <Route path="/admin/hospitals" element={<RoleGuard page="admin-hospitals"><HospitalManagement /></RoleGuard>} />
            <Route path="/admin/users" element={<RoleGuard page="admin-users"><UserManagement /></RoleGuard>} />
            <Route path="/admin/beds" element={<RoleGuard page="admin-beds"><BedManagement /></RoleGuard>} />
            <Route path="/shift-assignment" element={<RoleGuard page="shift-assignment" allowDesignations={['CHARGE_NURSE']}><ShiftAssignment /></RoleGuard>} />
            <Route path="/shift-planner" element={<RoleGuard page="shift-planner"><ShiftPlannerPage /></RoleGuard>} />
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
            <Route path="/icu" element={<RoleGuard page="icu"><IcuEscalationView /></RoleGuard>} />
            <Route path="/referral" element={<RoleGuard page="referral"><ReferralManagement /></RoleGuard>} />
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
        </div>

      </main>

      {/* ── Direct Resus floating action button (V28) ──
          Persistent on every authenticated page so a clinician can
          trigger Red-patient admission in one click from anywhere
          in the app. Self-gates: hides on /entry (the registration
          page already shows the Stable/Unstable banner), on
          unauthenticated routes, and for non-clinical roles. */}
      <DirectResusFAB />
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
