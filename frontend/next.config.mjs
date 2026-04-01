/** @type {import('next').NextConfig} */
const apiProxyTarget = process.env.RISKDESK_API_PROXY_TARGET?.replace(/\/$/, '');

if (!apiProxyTarget) {
  throw new Error('RISKDESK_API_PROXY_TARGET must be set in frontend/.env.local');
}

const nextConfig = {
  output: 'standalone',
  async rewrites() {
    const target = process.env.RISKDESK_API_PROXY_TARGET || 'http://localhost:8080';
    return [
      {
        source: '/api/:path*',
        destination: `${target}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
