import { Button } from 'react-bootstrap';

// Selection is lifted into the page. The child reports user intent through onToggle.
export default function SeatGrid({ seats, selectedIds, onToggle, disabled }) {
  return <div className="seat-grid" aria-label="Seats">
    {seats.map((seat) => {
      const selected = selectedIds.includes(seat.id);
      const available = seat.status === 'AVAILABLE';
      const variant = selected ? 'primary' : available ? 'outline-primary' : seat.status === 'HELD' ? 'warning' : 'secondary';
      return <Button key={seat.id} variant={variant} className="seat-button" disabled={disabled || !available}
        aria-pressed={selected} aria-label={`Seat ${seat.seatNumber}, ${seat.status}${selected ? ', selected' : ''}`}
        title={`${seat.seatNumber}: ${seat.status}`} onClick={() => onToggle(seat)}>{seat.seatNumber}</Button>;
    })}
  </div>;
}
