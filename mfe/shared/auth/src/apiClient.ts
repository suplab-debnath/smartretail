import axios from 'axios';
import { fetchAuthSession } from 'aws-amplify/auth';
import { getConfig } from './config.js';

export function createApiClient() {
  const config = getConfig();
  const instance = axios.create({ baseURL: config.apiGatewayEndpoint });

  instance.interceptors.request.use(async (reqConfig) => {
    try {
      const session = await fetchAuthSession();
      if (session.tokens?.idToken) {
        reqConfig.headers.Authorization = `Bearer ${session.tokens.idToken.toString()}`;
      }
    } catch (e) {
      // Not authenticated, send without token or ignore depending on route
    }
    return reqConfig;
  });

  instance.interceptors.response.use(
    response => response,
    async error => {
      if (error.response?.status === 401) {
        // Simple fallback to redirect to login or home if 401
        window.location.href = '/';
      }
      return Promise.reject(error);
    }
  );

  return instance;
}
