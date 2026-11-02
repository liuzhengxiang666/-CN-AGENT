//
// This is only a SKELETON file for the 'Phone Number' exercise. It's been provided as a
// convenience to get you started writing code faster.
//

export const clean = (number) => {
  if (/[a-zA-Z]/.test(number)) {
    throw new Error('Letters not permitted');
  }

  if (/[^0-9 ().+-]/.test(number)) {
    throw new Error('Punctuations not permitted');
  }

  const digits = number.replace(/[^0-9]/g, '');

  if (digits.length > 11) {
    throw new Error('More than 11 digits');
  }

  if (digits.length === 11 && digits[0] !== '1') {
    throw new Error('11 digits must start with 1');
  }

  if (digits.length !== 10 && digits.length !== 11) {
    throw new Error('Incorrect number of digits');
  }

  const subscriber = digits.length === 11 ? digits.slice(1) : digits;

  if (subscriber[0] === '0') {
    throw new Error('Area code cannot start with zero');
  }

  if (subscriber[0] === '1') {
    throw new Error('Area code cannot start with one');
  }

  if (subscriber[3] === '0') {
    throw new Error('Exchange code cannot start with zero');
  }

  if (subscriber[3] === '1') {
    throw new Error('Exchange code cannot start with one');
  }

  return subscriber;
};
