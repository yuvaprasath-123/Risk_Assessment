"""Offline regressions for handoff tooling, not application or Oracle tests."""
from pathlib import Path
from decimal import Decimal
import sys
sys.dont_write_bytecode=True
import tempfile
import unittest
from jsonschema import Draft202012Validator
from package_support import read_json,read_json_text,exact_json,sha256_file,runtime_fingerprint,verify_attestation,write_json

ROOT=Path(__file__).resolve().parents[1]
class PackageToolTests(unittest.TestCase):
    def test_exact_nested_numeric_roundtrip(self):
        value={"nested":[{"decimal":Decimal("1.0000000000000001"),"integer":9007199254740993,"scientific":Decimal("1.234567890123456789E-20")} ]}
        decoded=read_json_text(exact_json(value));self.assertEqual(value,decoded)
        self.assertIsInstance(decoded["nested"][0]["decimal"],Decimal)
        self.assertIsInstance(decoded["nested"][0]["integer"],int)
    def test_order_and_nulls_remain(self):
        value={"b":[3,None,1],"a":True};self.assertEqual(value,read_json_text(exact_json(value)))
    def test_duplicate_properties_rejected(self):
        with self.assertRaises(ValueError):read_json_text('{"a":1,"a":2}')
    def test_nonfinite_decode_rejected(self):
        for value in ["NaN","Infinity","-Infinity"]:
            with self.subTest(value=value),self.assertRaises(ValueError):read_json_text(value)
    def test_nonfinite_encode_rejected(self):
        with self.assertRaises(ValueError):exact_json(Decimal("NaN"))
    def test_binary_float_rejected(self):
        with self.assertRaises(ValueError):exact_json(1.1)
    def test_money_accepts_only_exact_scale_and_bounds(self):
        spec=read_json(ROOT/"openapi/business-configuration.openapi.json")
        schema=spec["components"]["schemas"]["ApprovalThresholdPut"]["properties"]["minAmountInclusive"]
        validator=Draft202012Validator(schema)
        for value in ["0","00.00","1.2","1.2300","9999999999999.99","0009999999999999.9900"]:
            with self.subTest(valid=value):self.assertTrue(validator.is_valid(value))
        for value in ["1.234","10000000000000","9999999999999.991","-1","1e2",1.25]:
            with self.subTest(invalid=value):self.assertFalse(validator.is_valid(value))
    def test_long_id_bounds(self):
        spec=read_json(ROOT/"openapi/risk-assessment.openapi.json");schema=spec["components"]["schemas"]["Submission"]["properties"]["paymentReference"]
        validator=Draft202012Validator(schema)
        for value in ["1","9007199254740993","9223372036854775807"]:self.assertTrue(validator.is_valid(value))
        for value in ["0","01","9223372036854775808","9999999999999999999",1]:self.assertFalse(validator.is_valid(value))
    def test_revision_bound_and_leading_zeroes(self):
        schema=read_json(ROOT/"openapi/business-configuration.openapi.json")["components"]["schemas"]["PublishRequest"]["properties"]["expectedRowVersion"]
        validator=Draft202012Validator(schema)
        for value in ["0","0000","0009223372036854775807",9223372036854775807]:self.assertTrue(validator.is_valid(value))
        for value in ["9223372036854775808",9223372036854775808,"-1"]:self.assertFalse(validator.is_valid(value))
    def test_saved_java_result_cannot_authorize_changed_examples(self):
        with tempfile.TemporaryDirectory(prefix="d2-document-regression-") as directory:
            repo=Path(directory);root=repo/"docs/handoff";root.mkdir(parents=True)
            (repo/"developer2-pom.xml").write_text("test")
            for module in ["risk-rule-contracts","internal-service-security","business-configuration-service","risk-assessment-service"]:
                (repo/module/"src/main").mkdir(parents=True);(repo/module/"pom.xml").write_text("test")
            sample=root/"fixture.json";write_json(sample,{"a":1})
            report={"attestationFormat":"sha256-lf-normalized-text-v1","verifiedFiles":{"fixture.json":sha256_file(sample)},"runtimeSourceSha256":runtime_fingerprint(repo)}
            verify_attestation(root,report)
            write_json(sample,{"a":2})
            with self.assertRaises(AssertionError):verify_attestation(root,report)
    def test_saved_java_result_cannot_authorize_changed_runtime_sources(self):
        with tempfile.TemporaryDirectory(prefix="d2-document-regression-") as directory:
            repo=Path(directory);root=repo/"docs/handoff";root.mkdir(parents=True)
            (repo/"developer2-pom.xml").write_text("test")
            for module in ["risk-rule-contracts","internal-service-security","business-configuration-service","risk-assessment-service"]:
                (repo/module/"src/main").mkdir(parents=True);(repo/module/"pom.xml").write_text("test")
            sample=root/"fixture.json";write_json(sample,{"a":1})
            report={"attestationFormat":"sha256-lf-normalized-text-v1","verifiedFiles":{"fixture.json":sha256_file(sample)},"runtimeSourceSha256":runtime_fingerprint(repo)}
            (repo/"risk-rule-contracts/src/main/test.txt").write_text("changed source fixture")
            with self.assertRaises(AssertionError):verify_attestation(root,report)

    def test_fingerprints_survive_git_line_ending_conversion(self):
        with tempfile.TemporaryDirectory(prefix="d2-document-regression-") as directory:
            repo=Path(directory);root=repo/"docs/handoff";root.mkdir(parents=True)
            (repo/"developer2-pom.xml").write_bytes(b"test\nline\n")
            for module in ["risk-rule-contracts","internal-service-security","business-configuration-service","risk-assessment-service"]:
                (repo/module/"src/main").mkdir(parents=True);(repo/module/"pom.xml").write_bytes(b"test\nline\n")
            sample=root/"fixture.json";sample.write_bytes(b'{\n  "a": 1\n}\n')
            report={"attestationFormat":"sha256-lf-normalized-text-v1","verifiedFiles":{"fixture.json":sha256_file(sample)},"runtimeSourceSha256":runtime_fingerprint(repo)}
            for file in repo.rglob("*"):
                if file.is_file():file.write_bytes(file.read_bytes().replace(b"\n",b"\r\n"))
            verify_attestation(root,report)
            sample.write_bytes(sample.read_bytes().replace(b'"a": 1',b'"a": 2'))
            with self.assertRaises(AssertionError):verify_attestation(root,report)

if __name__=="__main__":
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PackageToolTests))
    write_json(ROOT/"validation/tooling-regression-report.json",dict(tests=result.testsRun,failures=len(result.failures),errors=len(result.errors),skipped=len(result.skipped),oracleAccess=False,networkAccess=False))
    raise SystemExit(0 if result.wasSuccessful() else 1)
