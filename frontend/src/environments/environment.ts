export const environment = {
  production: true,
  appVersion: '0.1.0',
  apiUrl: '/api',
  wsUrl: '/ws',
  oidc: {
    pharmacy: {
      authority: 'https://idp.pharmacore.io/realms/pharmacore-pharmacies',
      clientId: 'pharmacore-pos',
    },
    platform: {
      authority: 'https://idp.pharmacore.io/realms/pharmacore-platform',
      clientId: 'pharmacore-console',
    },
    scope: 'openid profile email',
  },
};
