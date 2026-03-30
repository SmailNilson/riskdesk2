/** @type {import('next').NextConfig} */
const apiProxyTarget = process.env.RISKDESK_API_PROXY_TARGET?.replace(/\/$/, '');

if (!apiProxyTarget) {
  throw new Error('RISKDESK_API_PROXY_TARGET must be set in frontend/.env.local');
}

const nextConfig = {
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${apiProxyTarget}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
