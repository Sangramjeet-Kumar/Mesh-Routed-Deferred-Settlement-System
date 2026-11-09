export const money = (amount: number) => new Intl.NumberFormat('en-IN', {
  style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2,
}).format(amount);

export const time = (date: Date | string | number) => new Intl.DateTimeFormat('en-IN', {
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
}).format(new Date(date));

export const deviceName = (id: string) => ({
  'phone-alice': 'Alice’s phone',
  'phone-stranger1': 'Relay 01',
  'phone-stranger2': 'Relay 02',
  'phone-stranger3': 'Relay 03',
  'phone-bridge': 'Bridge node',
}[id] ?? id);
