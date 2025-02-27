from typing import Dict
from testUtils.junit.TestCase import TestCase, Result
from datetime import timedelta
from xml.etree import ElementTree as Et

class TestSuite:
    __cases: Dict[str, TestCase] = dict()

    name: str = ""
    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    time: timedelta = timedelta()


    def __init__(self, name: str):
        self.name = name

    def addCase(self, case: TestCase):
        self.__cases[case.name] = case
        self.tests += 1
        self.time += case.time

        if case.result == Result.ERROR:
            self.errors += 1
        elif case.result == Result.FAILURE:
            self.failures += 1
        elif case.result == Result.SKIPPED:
            self.skipped += 1

    def toXml(self):
        suite: Et.Element = Et.Element("testsuite")
        suite.set("name", self.name)
        suite.set("tests", str(self.tests))
        suite.set("failures", str(self.failures))
        suite.set("errors", str(self.errors))
        suite.set("skipped", str(self.skipped))
        suite.set("time", str(self.time.total_seconds()))

        for name, case in self.__cases.items():
            case.toXml(suite)
        return suite