import os
import signal
from pty import openpty
from subprocess import Popen
from time import sleep
from typing import List, Optional
from datetime import datetime
from io import TextIOWrapper
from threading import Thread
from datetime import datetime


def studSaveStrComp(ref: str, other: str):
    """
    Student save compare between strings.
    Converts both to lower, strips them and removes all non alphanumeric chars
    before comparision.
    """
    # Strip and convert to lower:
    ref = ref.strip().lower()
    other = other.strip().lower()

    # Remove all non alphanumeric chars:
    ref = "".join(c for c in ref if c.isalnum())
    other = "".join(c for c in other if c.isalnum())

    # print("Ref: {}\nOther:{}".format(ref, other))
    return ref == other

# A cache of all that the tester has been writing to stdout:
testerOutputCache: List[str] = list()

def clearTesterOutputCache():
    """
    Clears the testerOutputCache.
    """
    testerOutputCache.clear()

def getTesterOutput():
    """
    Returns the complet tester output as a single string.
    """
    return "\n".join(testerOutputCache)

def __getCurDateTimeStr():
    """
    Returns the current date and time string (e.g. 11.10.2019_17:02:33)
    """
    return datetime.now().strftime("%d.%m.%Y_%H:%M:%S")

def printTester(text: str):
    """
    Prints the given string with the '[TESTER]: ' tag in front.
    Should be used instead of print() to make it easier for students
    to determin what came from the tester and what from their programm.
    """
    msg: str = "[{}][TESTER]: {}".format(__getCurDateTimeStr(), text)
    print(msg)
    testerOutputCache.append(msg)


def printProg(text: str):
    """
    Prints the given string with the '[PROG]: ' tag in front.
    Should be used instead of print() to make it easier for students
    to determin what came from the tester and what from their programm.
    """
    msg: str = "[{}][PROG]: {}".format(__getCurDateTimeStr(), text)
    print(msg)
    testerOutputCache.append(msg)


class ReadCache(Thread):
    """
    Helper class that makes sure we only get one line (seperated by '\n')
    if we read multiple lines at once.
    """
    __cacheList: List[str]
    __cacheFile: TextIOWrapper

    __outFd: int
    __outSlaveFd: int

    def __init__(self, filePath: str):
        Thread.__init__(self)
        self.__cacheList = []
        self.__cacheFile = open(filePath, "w")

        # Emulate a terminal:
        self.__outFd, self.__outSlaveFd = openpty()

        self.start()

    def fileno(self):
        return self.__outFd

    def join(self):
        os.close(self.__outFd)
        os.close(self.__outSlaveFd)
        Thread.join(self)

    def __isFdValid(self, fd: int):
        try:
            os.stat(fd)
        except OSError:
            return False
        return True

    def run(self):
        self.__shouldRun = True
        while self.__isFdValid(self.__outSlaveFd):
            try:
                data: bytes = os.read(self.__outSlaveFd, 4096)
            except OSError:
                break
            if data is not None:
                dataStr: str = data.decode()
                self.__cacheFile.write(dataStr)
                self.__cacheFile.flush()
                self.__cache(dataStr)

    def canReadLine(self):
        return len(self.__cacheList) > 0

    def __cache(self, data: str):
        self.__cacheList.extend(data.splitlines(True))

    def readLine(self):
        if self.canReadLine():
            return self.__cacheList.pop(0)
        return ""


class PWrap:
    """
    A wrapper for "Popen".
    """

    cmd: List[str]
    prog: Optional[Popen] = None

    __stdinFd: int
    __stdinMasterFd: int

    __stdOutLineCache: ReadCache
    __stdErrLineCache: ReadCache

    def __init__(self, cmd: List[str], stdoutFilePath: str = "/tmp/stdout.txt", stderrFilePath: str = "/tmp/stderr.txt"):
        self.cmd = cmd
        self.stdout = open(stdoutFilePath, "wb")
        self.stderr = open(stderrFilePath, "wb")

        self.__stdOutLineCache = ReadCache(stdoutFilePath)
        self.__stdErrLineCache = ReadCache(stderrFilePath)

    def __del__(self):
        os.close(self.__stdinFd)
        os.close(self.__stdinMasterFd)

    def start(self):
        """
        Starts the process and sets all file descriptors to nonblocking.
        """
        # Emulate a terminal for stdin:
        self.__stdinMasterFd, self.__stdinFd = openpty()

        # Start the actual process:
        self.prog = Popen(self.cmd,
                          stdout=self.__stdOutLineCache.fileno(),
                          stdin=self.__stdinMasterFd,
                          stderr=self.__stdErrLineCache.fileno(),
                          universal_newlines=True,
                          preexec_fn=os.setsid) # Make sure we store the process group id

    def readLineStdout(self, blocking: bool = True):
        """
        Reads a single line from the processes stdout and returns it.

        ---

        blocking:
            When set to True will only return if the process terminated or we read a non empty string.
        """
        while blocking:
            if not self.__stdOutLineCache.canReadLine():
                sleep(0.1)
            else:
                line: str = self.__stdOutLineCache.readLine()
                printProg(line)
                return line

    def canReadLineStdout(self):
        """
        Returns whether there is a line from the processes stdout that can be read.
        """
        return self.__stdOutLineCache.canReadLine()

    def readLineStderr(self, blocking: bool = True):
        """
        Reads a single line from the processes stderr and returns it.

        ---

        blocking:
            When set to True will only return if the process terminated or we read a non empty string.
        """
        while blocking:
            if not self.__stdErrLineCache.canReadLine():
                sleep(0.1)
            else:
                line: str = self.__stdErrLineCache.readLine()
                printProg(line)
                return line

    def canReadLineStderr(self):
        """
        Returns whether there is a line from the processes stderr that can be read.
        """
        return self.__stdErrLineCache.canReadLine()

    def writeStdin(self, data: str):
        """
        Writes the given data string to the processes stdin.
        """
        os.write(self.__stdinFd, data.encode())
        printTester("Wrote: {}".format(data))

    def hasTerminated(self):
        """
        Returns whether the process has terminated.
        """
        return self.prog is None or self.prog.poll() is not None

    def getReturnCode(self):
        """
        Returns the returncode of the terminated process else None.
        """
        return self.prog.returncode

    def waitUntilTerminationReading(self, secs: float = -1):
        """
        Waits until termination of the process and tries to read until either
        the process terminated or the timeout occurred.

        Returns True if the process terminated before the timeout occurred,
        else False.

        ---

        secs:
            The timeout in seconds. Values < 0 result in infinity.
        """
        start: datetime = datetime.now()
        while True:
            if self.hasTerminated():
                return True
            elif secs >= 0 and (datetime.now() - start).total_seconds() >= secs:
                return False
            self.readLineStdout(False)
            sleep(0.1)

    def kill(self, signal: int = signal.SIGTERM):
        """
        Sends the given signal to the complet process group started by the process.

        ---

        signal:
            The signal that should be sent to the process group started by the process.
        """
        # Send a signal to the complete process group:
        os.killpg(os.getpgid(self.prog.pid), signal)

    def cleanup(self):
        """
        Should be called once the execution has terminated.
        Will join the stdout and stderr reader threads.
        """

        self.__stdOutLineCache.join()
        self.__stdErrLineCache.join()

    def getPID(self):
        return self.prog.pid