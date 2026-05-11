import { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Shield, User, Lock, Phone, BadgeCheck, Loader2, AlertTriangle,
  CheckCircle2, Eye, EyeOff, Building2, Briefcase,
} from 'lucide-react';
import { authApi } from '@/api/auth';
import type { InvitationTokenInfo } from '@/api/types';

export function ActivateAccountPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const token = searchParams.get('token') || '';

  // Token validation state
  const [tokenInfo, setTokenInfo] = useState<InvitationTokenInfo | null>(null);
  const [validating, setValidating] = useState(true);
  const [tokenError, setTokenError] = useState<string | null>(null);

  // Form state
  const [form, setForm] = useState({
    firstName: '',
    lastName: '',
    password: '',
    confirmPassword: '',
    phoneNumber: '',
    employeeNumber: '',
    professionalLicense: '',
  });
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [success, setSuccess] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Validate token on mount
  useEffect(() => {
    if (!token) {
      setTokenError('No invitation token provided. Please use the link from your invitation email.');
      setValidating(false);
      return;
    }
    authApi.validateToken(token)
      .then((info) => {
        if (info.expired) {
          setTokenError('This invitation has expired. Please ask your administrator to resend the invitation.');
        } else if (info.used) {
          setTokenError('This invitation has already been used. If you need access, contact your administrator.');
        } else {
          setTokenInfo(info);
        }
      })
      .catch(() => {
        setTokenError('Invalid or expired invitation token. Please contact your administrator.');
      })
      .finally(() => setValidating(false));
  }, [token]);

  const passwordsMatch = form.password === form.confirmPassword;
  const passwordValid = form.password.length >= 8;
  const canSubmit = form.firstName && form.lastName && passwordValid && passwordsMatch && !submitting;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      await authApi.activate({
        token,
        firstName: form.firstName,
        lastName: form.lastName,
        password: form.password,
        phoneNumber: form.phoneNumber || undefined,
        employeeNumber: form.employeeNumber || undefined,
        professionalLicense: form.professionalLicense || undefined,
      });
      setSuccess(true);
    } catch (err: any) {
      setError(err?.message || 'Failed to activate account. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  // Loading state
  if (validating) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900 flex items-center justify-center">
        <div className="text-center">
          <Loader2 className="w-8 h-8 animate-spin text-cyan-400 mx-auto mb-4" />
          <p className="text-white/60 text-sm">Validating your invitation...</p>
        </div>
      </div>
    );
  }

  // Token error state
  if (tokenError) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900 flex items-center justify-center p-4">
        <div className="max-w-md w-full bg-white/5 backdrop-blur-xl border border-white/10 rounded-3xl p-8 text-center">
          <div className="w-16 h-16 rounded-2xl bg-red-500/20 flex items-center justify-center mx-auto mb-5">
            <AlertTriangle className="w-8 h-8 text-red-400" />
          </div>
          <h1 className="text-xl font-bold text-white mb-3">Invalid Invitation</h1>
          <p className="text-white/50 text-sm mb-6 leading-relaxed">{tokenError}</p>
          <button
            onClick={() => navigate('/login')}
            className="px-6 py-3 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-sm font-bold hover:-translate-y-0.5 transition-all"
          >
            Go to Login
          </button>
        </div>
      </div>
    );
  }

  // Success state
  if (success) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900 flex items-center justify-center p-4">
        <div className="max-w-md w-full bg-white/5 backdrop-blur-xl border border-white/10 rounded-3xl p-8 text-center">
          <div className="w-16 h-16 rounded-2xl bg-emerald-500/20 flex items-center justify-center mx-auto mb-5">
            <CheckCircle2 className="w-8 h-8 text-emerald-400" />
          </div>
          <h1 className="text-xl font-bold text-white mb-3">Account Activated!</h1>
          <p className="text-white/50 text-sm mb-6 leading-relaxed">
            Your account has been set up successfully. You can now log in with your email and the password you just created.
          </p>
          <button
            onClick={() => navigate('/login')}
            className="px-6 py-3 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-sm font-bold hover:-translate-y-0.5 transition-all"
          >
            Go to Login
          </button>
        </div>
      </div>
    );
  }

  // Activation form
  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900 flex items-center justify-center p-4">
      <div className="max-w-lg w-full">
        {/* Header */}
        <div className="text-center mb-8">
          <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-cyan-500/30 to-blue-500/30 flex items-center justify-center mx-auto mb-4 border border-cyan-500/20">
            <Shield className="w-7 h-7 text-cyan-400" />
          </div>
          <h1 className="text-2xl font-bold text-white tracking-tight">Complete Your Account</h1>
          <p className="text-white/40 text-sm mt-2">SmartTriage — Emergency Department System</p>
        </div>

        {/* Invitation info */}
        {tokenInfo && (
          <div className="bg-white/5 backdrop-blur-xl border border-white/10 rounded-2xl p-4 mb-5">
            <div className="flex flex-wrap items-center gap-4 text-sm">
              <div className="flex items-center gap-2">
                <BadgeCheck className="w-4 h-4 text-cyan-400" />
                <span className="text-white/50">Email:</span>
                <span className="text-white font-semibold">{tokenInfo.email}</span>
              </div>
              <div className="flex items-center gap-2">
                <Briefcase className="w-4 h-4 text-violet-400" />
                <span className="text-white/50">Role:</span>
                <span className="text-white font-semibold">{tokenInfo.role.replace(/_/g, ' ')}</span>
              </div>
              <div className="flex items-center gap-2">
                <Building2 className="w-4 h-4 text-amber-400" />
                <span className="text-white/50">Hospital:</span>
                <span className="text-white font-semibold">{tokenInfo.hospitalName}</span>
              </div>
            </div>
          </div>
        )}

        {/* Form */}
        <form onSubmit={handleSubmit} className="bg-white/5 backdrop-blur-xl border border-white/10 rounded-3xl p-6 space-y-4">

          {error && (
            <div className="flex items-center gap-2 px-4 py-3 rounded-xl bg-red-500/15 border border-red-500/20 text-red-400 text-sm font-semibold">
              <AlertTriangle className="w-4 h-4 shrink-0" />
              {error}
            </div>
          )}

          {/*
            Anti-autofill defence: Chrome (and most password managers)
            IGNORE autocomplete="off" on text fields when they have
            remembered credentials for the current origin — which is
            why the previous workaround leaked the admin's email into
            the invitee's Last Name field. The reliable mitigation is:

              - autoComplete="new-password" — Chrome explicitly excludes
                this value from its autofill heuristic, and unlike "off"
                it cannot be silently overridden.
              - data-1p-ignore + data-lpignore — instruct 1Password and
                LastPass to skip these inputs.
              - No `name` attribute at all — without a name there is no
                pattern for Chrome to match to a "family-name" /
                "given-name" field. The form is submitted from React
                state, not from form-data, so the name is purely
                decorative.

            Visual + invisible honeypot input below the two fields
            absorbs any autofill that does sneak through; whatever lands
            there is discarded.
          */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-[10px] font-bold uppercase tracking-wider text-white/40 mb-1.5">First Name *</label>
              <div className="relative">
                <User className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-white/20" />
                <input
                  type="text"
                  value={form.firstName}
                  onChange={(e) => setForm({ ...form, firstName: e.target.value })}
                  placeholder="John"
                  autoComplete="new-password"
                  data-1p-ignore
                  data-lpignore="true"
                  className="w-full pl-10 pr-3 py-3 bg-white/5 border border-white/10 rounded-xl text-sm text-white placeholder-white/20 outline-none focus:border-cyan-500/50 transition-colors"
                />
              </div>
            </div>
            <div>
              <label className="block text-[10px] font-bold uppercase tracking-wider text-white/40 mb-1.5">Last Name *</label>
              <div className="relative">
                <User className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-white/20" />
                <input
                  type="text"
                  value={form.lastName}
                  onChange={(e) => setForm({ ...form, lastName: e.target.value })}
                  placeholder="Doe"
                  autoComplete="new-password"
                  data-1p-ignore
                  data-lpignore="true"
                  className="w-full pl-10 pr-3 py-3 bg-white/5 border border-white/10 rounded-xl text-sm text-white placeholder-white/20 outline-none focus:border-cyan-500/50 transition-colors"
                />
              </div>
            </div>
          </div>

          {/* Honeypot — invisible decoy that absorbs Chrome's email-fill
              heuristic when it ignores autoComplete="new-password" on
              the real fields. Position off-screen so users never see
              or interact with it. The form does not read its value. */}
          <input
            type="text"
            tabIndex={-1}
            aria-hidden="true"
            autoComplete="username"
            style={{ position: 'absolute', left: '-9999px', width: '1px', height: '1px', opacity: 0 }}
          />

          <div>
            <label className="block text-[10px] font-bold uppercase tracking-wider text-white/40 mb-1.5">Password *</label>
            <div className="relative">
              <Lock className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-white/20" />
              <input
                type={showPassword ? 'text' : 'password'}
                value={form.password}
                onChange={(e) => setForm({ ...form, password: e.target.value })}
                placeholder="Min 8 characters"
                className="w-full pl-10 pr-10 py-3 bg-white/5 border border-white/10 rounded-xl text-sm text-white placeholder-white/20 outline-none focus:border-cyan-500/50 transition-colors"
              />
              <button
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-white/30 hover:text-white/60"
              >
                {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
            {form.password && !passwordValid && (
              <p className="text-red-400 text-xs mt-1">Password must be at least 8 characters</p>
            )}
          </div>

          <div>
            <label className="block text-[10px] font-bold uppercase tracking-wider text-white/40 mb-1.5">Confirm Password *</label>
            <div className="relative">
              <Lock className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-white/20" />
              <input
                type={showPassword ? 'text' : 'password'}
                value={form.confirmPassword}
                onChange={(e) => setForm({ ...form, confirmPassword: e.target.value })}
                placeholder="Re-enter password"
                className="w-full pl-10 pr-3 py-3 bg-white/5 border border-white/10 rounded-xl text-sm text-white placeholder-white/20 outline-none focus:border-cyan-500/50 transition-colors"
              />
            </div>
            {form.confirmPassword && !passwordsMatch && (
              <p className="text-red-400 text-xs mt-1">Passwords do not match</p>
            )}
          </div>

          <div>
            <label className="block text-[10px] font-bold uppercase tracking-wider text-white/40 mb-1.5">Phone Number</label>
            <div className="relative">
              <Phone className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-white/20" />
              <input
                value={form.phoneNumber}
                onChange={(e) => setForm({ ...form, phoneNumber: e.target.value })}
                placeholder="+250 788 000 000"
                className="w-full pl-10 pr-3 py-3 bg-white/5 border border-white/10 rounded-xl text-sm text-white placeholder-white/20 outline-none focus:border-cyan-500/50 transition-colors"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-[10px] font-bold uppercase tracking-wider text-white/40 mb-1.5">Employee Number</label>
              <input
                value={form.employeeNumber}
                onChange={(e) => setForm({ ...form, employeeNumber: e.target.value })}
                placeholder="EMP-001"
                className="w-full px-3 py-3 bg-white/5 border border-white/10 rounded-xl text-sm text-white placeholder-white/20 outline-none focus:border-cyan-500/50 transition-colors"
              />
            </div>
            <div>
              <label className="block text-[10px] font-bold uppercase tracking-wider text-white/40 mb-1.5">Professional License</label>
              <input
                value={form.professionalLicense}
                onChange={(e) => setForm({ ...form, professionalLicense: e.target.value })}
                placeholder="LIC-001"
                className="w-full px-3 py-3 bg-white/5 border border-white/10 rounded-xl text-sm text-white placeholder-white/20 outline-none focus:border-cyan-500/50 transition-colors"
              />
            </div>
          </div>

          <button
            type="submit"
            disabled={!canSubmit}
            className="w-full py-3.5 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-sm font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50 disabled:hover:translate-y-0 flex items-center justify-center gap-2"
          >
            {submitting ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                Activating...
              </>
            ) : (
              <>
                <CheckCircle2 className="w-4 h-4" />
                Activate Account
              </>
            )}
          </button>
        </form>

        <p className="text-center text-white/20 text-xs mt-6">
          Already have an account?{' '}
          <button onClick={() => navigate('/login')} className="text-cyan-400 hover:text-cyan-300 font-semibold">
            Log in
          </button>
        </p>
      </div>
    </div>
  );
}
