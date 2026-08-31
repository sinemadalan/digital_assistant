/// Enum to represent the various outcomes of the enrollment process.
enum EnrollResult {
  success,             // 200/201: Successfully enrolled and token saved
  tokenAlreadyExists,  // Local token already exists, skipped network request
  invalidCode,         // 400: Invalid enrollment key
  duplicateEnrollment, // 409: Duplicate device or key already used
  serverError,         // 500 or other unhandled status codes
  networkError         // Connection drops, timeouts, etc.
}