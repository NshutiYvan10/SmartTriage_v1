import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

export function LandingPage() {
  const navigate = useNavigate();

  useEffect(() => {
    // Listen for navigation messages from the landing page iframe
    const handleMessage = (event: MessageEvent) => {
      if (event.data === 'navigate-dashboard') {
        navigate('/dashboard');
      }
    };
    window.addEventListener('message', handleMessage);

    // Intercept clicks on the iframe that navigate to /dashboard
    const handleIframeNav = () => {
      const iframe = document.getElementById('landing-iframe') as HTMLIFrameElement;
      if (!iframe?.contentWindow) return;
      try {
        const links = iframe.contentDocument?.querySelectorAll('a[href="/dashboard"]');
        links?.forEach((link) => {
          link.addEventListener('click', (e) => {
            e.preventDefault();
            navigate('/dashboard');
          });
        });
      } catch {
        // Cross-origin — handled by postMessage
      }
    };

    const iframe = document.getElementById('landing-iframe') as HTMLIFrameElement;
    if (iframe) {
      iframe.addEventListener('load', handleIframeNav);
    }

    return () => {
      window.removeEventListener('message', handleMessage);
      if (iframe) {
        iframe.removeEventListener('load', handleIframeNav);
      }
    };
  }, [navigate]);

  return (
    <div className="fixed inset-0 z-[9999] bg-[#020b14]">
      <iframe
        id="landing-iframe"
        src="/landing/index.html"
        className="w-full h-full border-0"
        title="Smart Triage Landing"
      />
    </div>
  );
}
