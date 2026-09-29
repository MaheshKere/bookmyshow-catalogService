import { useEffect, useState } from 'react';
import { useDispatch } from 'react-redux';
import { fetchBookingStatus } from '../features/booking/bookingSlice.js';
import { terminalBooking } from '../services/format.js';

export const POLL_INTERVAL_MS = 3000;
export const POLL_LIMIT_MS = 60000;

export default function useBookingPolling(reference) {
  const dispatch = useDispatch();
  const [run, setRun] = useState(0);
  const [polling, setPolling] = useState(false);
  const [message, setMessage] = useState('');

  useEffect(() => {
    let active = true;
    let nextTimer;
    let request;
    setPolling(true); setMessage('Checking your booking and payment status...');
    const deadline = setTimeout(() => {
      active = false; clearTimeout(nextTimer); request?.abort(); setPolling(false);
      setMessage('Automatic checks stopped after 60 seconds. Your booking may still be processing. Check again when ready.');
    }, POLL_LIMIT_MS);

    async function check() {
      request = dispatch(fetchBookingStatus(reference));
      try {
        const result = await request.unwrap();
        if (!active) return;
        if (terminalBooking(result.booking.status)) {
          setPolling(false); setMessage('The booking has reached its final status.'); clearTimeout(deadline);
        } else if (result.paymentError) {
          setPolling(false); setMessage('Payment status could not be read. Check the error below before retrying.'); clearTimeout(deadline);
        } else {
          // Schedule only after the prior calls finish: no overlapping requests.
          nextTimer = setTimeout(check, POLL_INTERVAL_MS);
        }
      } catch {
        if (!active) return;
        clearTimeout(deadline); setPolling(false); setMessage('Automatic checks stopped after an error. You can retry.');
      }
    }
    check();
    // Effects own their timers/requests. Cleanup runs on unmount, reference change, and StrictMode's dev check.
    return () => { active = false; clearTimeout(deadline); clearTimeout(nextTimer); request?.abort(); };
  }, [reference, dispatch, run]);

  return { polling, message, checkAgain: () => setRun((previous) => previous + 1) };
}
