//
// This is only a SKELETON file for the 'Phone Number' exercise. It's been provided as a
// convenience to get you started writing code faster.
//

export const clean = (phoneNumber) => {
  if (/[a-zA-Z]/.test(phoneNumber)) {
    throw new Error('Letters not permitted');
  }

  if (/[^0-9+().\s-]/.test(phoneNumber)) {
    throw new Error('Punctuations not permitted');
  }

  let digits = phoneNumber.replace(/\D/g, '');

  if (digits.length > 11) {
    throw new Error('More than 11 digits');
  }

  if (digits.length < 10) {
    throw new Error('Incorrect number of digits');
  }

  if (digits.length === 11) {
    if (digits[0] !== '1') {
      throw new Error('11 digits must start with 1');
    }
    digits = digits.slice(1);
  }

  if (digits[0] === '0') {
    throw new Error('Area code cannot start with zero');
  }

  if (digits[0] === '1') {
    throw new Error('Area code cannot start with one');
  }

  if (digits[3] === '0') {
    throw new Error('Exchange code cannot start with zero');
  }

  if (digits[3] === '1') {
    throw new Error('Exchange code cannot start with one');
  }

  return digits;
};
