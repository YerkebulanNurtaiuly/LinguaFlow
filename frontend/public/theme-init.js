// Applies the saved theme before first paint (see ThemeProvider); storage errors are ignored.
// A file of its own rather than an inline script, so the Content-Security-Policy can
// allow scripts from 'self' only (SEC-01).
try {
  const theme = localStorage.getItem('lf-theme')
  if (theme === 'light' || theme === 'dark') {
    document.documentElement.dataset.theme = theme
  }
} catch {
  // Private mode or blocked storage: keep the system theme
}
