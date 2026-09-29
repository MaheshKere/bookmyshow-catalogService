import { Badge } from 'react-bootstrap';

const colors = { AVAILABLE: 'light', HELD: 'warning', BOOKED: 'secondary', ACTIVE: 'primary',
  PENDING: 'warning', CONFIRMED: 'success', CANCELLED: 'secondary', EXPIRED: 'secondary', SUCCESS: 'success', FAILED: 'danger' };
export default function StatusBadge({ status }) {
  return <Badge bg={colors[status] || 'secondary'} text={['PENDING', 'HELD', 'AVAILABLE'].includes(status) ? 'dark' : undefined}>
    {status}
  </Badge>;
}
