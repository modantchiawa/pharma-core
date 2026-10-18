export const environment = {
  production: false,
  appVersion: '0.1.0-dev',
  apiUrl: 'http://localhost:8080/api',
  wsUrl: 'ws://localhost:8080/ws',
  oidc: {
    pharmacy: {
      authority: 'http://localhost:8180/realms/pharmacore-pharmacies',
      clientId: 'pharmacore-pos',
    },
    platform: {
      authority: 'http://localhost:8180/realms/pharmacore-platform',
      clientId: 'pharmacore-console',
    },
    scope: 'openid profile email',
  },
};
