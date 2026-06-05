import React, { createContext, useContext, useEffect, useState } from 'react';
import { Amplify } from 'aws-amplify';
import { fetchAuthSession, signInWithRedirect, signOut as amplifySignOut } from 'aws-amplify/auth';
import { Hub } from 'aws-amplify/utils';
import { jwtDecode } from 'jwt-decode';
import { getConfig } from './config.js';

interface User {
  sub: string;
  email: string;
  groups: string[];
}

export interface AuthState {
  user: User | null;
  token: string | null;
  isLoading: boolean;
  isAuthenticated: boolean;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
  hasRole: (role: string) => boolean;
}

const AuthContext = createContext<AuthState | null>(null);

interface AuthProviderProps {
  children: React.ReactNode;
  redirectSignIn?: string;
  redirectSignOut?: string;
}

export function AuthProvider({ children, redirectSignIn, redirectSignOut }: AuthProviderProps) {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const config = getConfig();
    if (config.cognitoPoolId) {
      const callbackUrl  = redirectSignIn  ?? (window.location.origin + '/callback');
      const logoutUrl    = redirectSignOut ?? (window.location.origin + '/logout');
      Amplify.configure({
        Auth: {
          Cognito: {
            userPoolId: config.cognitoPoolId,
            userPoolClientId: config.cognitoClientId,
            loginWith: {
              oauth: {
                domain: config.cognitoDomain,
                scopes: ['openid', 'email', 'profile'],
                redirectSignIn: [callbackUrl],
                redirectSignOut: [logoutUrl],
                responseType: 'code'
              }
            }
          }
        }
      });
    }

    const unsubscribe = Hub.listen('auth', ({ payload }) => {
      if (payload.event === 'signInWithRedirect') {
        checkUser();
      } else if (payload.event === 'signInWithRedirect_failure') {
        console.error('OAuth redirect failure', payload.data);
        setIsLoading(false);
      }
    });

    checkUser();

    return () => unsubscribe();
  }, []);

  async function checkUser() {
    // If the URL contains PKCE params, Amplify is mid-exchange.
    // Keep isLoading=true so components don't call signIn() and trigger a second redirect.
    // The Hub 'signInWithRedirect' event will call checkUser() again once tokens are ready.
    const pendingPkce = window.location.search.includes('code=') &&
                        window.location.search.includes('state=');
    try {
      const session = await fetchAuthSession();
      if (session.tokens?.idToken) {
        const idToken = session.tokens.idToken.toString();
        const decoded = jwtDecode<any>(idToken);
        setToken(idToken);
        setUser({
          sub: decoded.sub || '',
          email: decoded.email || '',
          groups: decoded['cognito:groups'] || []
        });
        setIsLoading(false);
      } else {
        setUser(null);
        setToken(null);
        if (!pendingPkce) setIsLoading(false);
      }
    } catch (err) {
      console.warn('No active session. Waiting for sign-in.');
      setUser(null);
      setToken(null);
      if (!pendingPkce) setIsLoading(false);
    }
  }

  const signIn = async () => {
    // If running locally without Cognito, mock login
    if (!getConfig().cognitoPoolId) {
      console.log('Local dev: mocking sign-in');
      setToken('mock-token');
      setUser({ sub: 'local', email: 'local@test.com', groups: ['EXECUTIVE', 'SC_PLANNER', 'STORE_MANAGER'] });
      setIsLoading(false);
      return;
    }
    await signInWithRedirect();
  };

  const signOut = async () => {
    if (!getConfig().cognitoPoolId) {
      setUser(null);
      setToken(null);
      return;
    }
    await amplifySignOut();
    setUser(null);
    setToken(null);
  };

  const hasRole = (role: string) => {
    return user?.groups.includes(role) || false;
  };

  const value: AuthState = {
    user,
    token,
    isLoading,
    isAuthenticated: !!user,
    signIn,
    signOut,
    hasRole
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
