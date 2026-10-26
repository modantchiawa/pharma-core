import { loginErrorMessage } from './login-page';

describe('loginErrorMessage', () => {
  const api = 'http://localhost:8081/api';

  it('shows the server detail when PharmaCore answered', () => {
    expect(
      loginErrorMessage(
        { status: 422, error: { detail: 'Identifiant ou mot de passe incorrect' } },
        api,
      ),
    ).toBe('Identifiant ou mot de passe incorrect');
  });

  it('names the address when another program answers or nothing does', () => {
    expect(loginErrorMessage({ status: 0 }, api)).toContain(
      'Serveur injoignable (http://localhost:8081/api)',
    );
    expect(loginErrorMessage({ status: 404 }, api)).toContain(
      'Aucun serveur PharmaCore à http://localhost:8081/api (HTTP 404)',
    );
    expect(loginErrorMessage({ status: 403 }, api)).toContain('ALLOWED_ORIGINS');
    expect(loginErrorMessage({ status: 502 }, api)).toContain('HTTP 502');
  });
});
