// LD_PRELOAD shim for running QAIRT tools under Rosetta: Rosetta has no priority-inheritance futexes, so
// pthread_mutex_init fails with EOPNOTSUPP for PTHREAD_PRIO_INHERIT mutexes (Qualcomm's hogl logger uses one).
// Drop the protocol and robustness requests; a plain mutex behaves the same for a single-process tool.
#define _GNU_SOURCE
#include <pthread.h>

int pthread_mutexattr_setprotocol(pthread_mutexattr_t *attr, int protocol) {
  (void)attr;
  (void)protocol;
  return 0;
}

int pthread_mutexattr_setrobust(pthread_mutexattr_t *attr, int robustness) {
  (void)attr;
  (void)robustness;
  return 0;
}
