import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactNode } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { AppLayout } from './layouts/AppLayout';
import { DeveloperGuidePage } from './pages/DeveloperGuidePage';
import { OverviewPage } from './pages/OverviewPage';
import { RequestLogsPage } from './pages/RequestLogsPage';
import { RoutesPage } from './pages/RoutesPage';
import { SignInPage } from './pages/SignInPage';
import { SystemStatusPage } from './pages/SystemStatusPage';
import { TenantsPage } from './pages/TenantsPage';
import { UsagePage } from './pages/UsagePage';
import { isSignedIn } from './api/session';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      staleTime: 10000,
    },
  },
});

function Protected({ children }: { children: ReactNode }) {
  if (!isSignedIn()) {
    return <Navigate to="/signin" replace />;
  }
  return <AppLayout>{children}</AppLayout>;
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <Routes>
        <Route path="/signin" element={<SignInPage />} />
        <Route path="/" element={<Protected><OverviewPage /></Protected>} />
        <Route path="/tenants" element={<Protected><TenantsPage /></Protected>} />
        <Route path="/routes" element={<Protected><RoutesPage /></Protected>} />
        <Route path="/usage" element={<Protected><UsagePage /></Protected>} />
        <Route path="/request-logs" element={<Protected><RequestLogsPage /></Protected>} />
        <Route path="/system" element={<Protected><SystemStatusPage /></Protected>} />
        <Route path="/guide" element={<Protected><DeveloperGuidePage /></Protected>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </QueryClientProvider>
  );
}
