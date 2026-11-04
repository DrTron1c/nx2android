# Python's multiprocessing locks are POSIX named semaphores, which an Android app may not create (sem_open: Permission denied). On Android they become pipes that hold one
# byte per count: fork keeps them shared, which is all the engine's tools need. They run with the fork start method for the same reason: a pipe cannot be handed to a fresh process.
# This file is found through PYTHONPATH (the app's Env.java) and does nothing elsewhere.
import sys


def _install():
    import _multiprocessing, errno, fcntl, os, select, struct, termios, threading, time

    RECURSIVE_MUTEX, SEMAPHORE = 0, 1

    class PipeSemLock:
        SEM_VALUE_MAX = 4096   # a pipe holds 64 KB; a Queue with no limit takes this as its size

        def __init__(self, kind, value, maxvalue, name=None, unlink=True):
            self.kind, self.maxvalue, self.name = kind, min(maxvalue, self.SEM_VALUE_MAX), None
            self._r, self._w = os.pipe()
            os.set_blocking(self._r, False)
            if value: os.write(self._w, b'\0' * min(value, self.SEM_VALUE_MAX))
            self._owner, self._depth = None, 0
            self.handle = self._r

        @staticmethod
        def _me(): return (os.getpid(), threading.get_ident())

        def _get_value(self):
            return struct.unpack('i', fcntl.ioctl(self._r, termios.FIONREAD, b'\0\0\0\0'))[0]

        def _is_zero(self): return self._get_value() == 0
        def _count(self): return self._depth
        def _is_mine(self): return self._depth > 0 and self._owner == self._me()
        def _after_fork(self): self._owner, self._depth = None, 0

        def acquire(self, block=True, timeout=None):
            if self.kind == RECURSIVE_MUTEX and self._is_mine(): self._depth += 1; return True
            end = None if timeout is None or not block else time.monotonic() + max(timeout, 0)
            while True:
                try:
                    if os.read(self._r, 1):
                        self._owner = self._me(); self._depth += 1
                        return True
                except BlockingIOError: pass
                except InterruptedError: continue
                if not block: return False
                left = None if end is None else end - time.monotonic()
                if left is not None and left <= 0: return False
                try: select.select([self._r], [], [], left)
                except InterruptedError: pass

        def release(self):
            if self.kind == RECURSIVE_MUTEX:
                if not self._is_mine(): raise AssertionError('attempt to release recursive lock not owned by thread')
                if self._depth > 1: self._depth -= 1; return
            elif self.maxvalue == 1 and self._get_value() >= 1: raise ValueError('semaphore or lock released too many times')
            if self._get_value() >= self.maxvalue: raise ValueError('semaphore or lock released too many times')
            self._owner = None; self._depth = max(self._depth - 1, 0)
            while True:
                try: os.write(self._w, b'\0'); return
                except InterruptedError: continue
                except OSError as e:
                    if e.errno != errno.EAGAIN: raise
                    time.sleep(0.001)

        def __enter__(self): self.acquire(); return self
        def __exit__(self, *a): self.release()

    _multiprocessing.SemLock = PipeSemLock
    _multiprocessing.sem_unlink = lambda name: None
    import multiprocessing
    multiprocessing.set_start_method('fork', force=True)


if hasattr(sys, 'getandroidapilevel') or sys.platform == 'android' or __import__('os').environ.get('NX2A_PIPE_LOCKS'):
    _install()
