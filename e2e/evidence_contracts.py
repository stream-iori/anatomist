"""Frame Anatomist evidence without confusing diff records with semantic streams."""
import json
import re
from pathlib import Path


def frames(output):
    decoder = json.JSONDecoder()
    values = []
    offset = 0
    active = False
    while offset < len(output):
        while offset < len(output) and output[offset].isspace():
            offset += 1
        if offset == len(output):
            break
        try:
            value, end = decoder.raw_decode(output, offset)
        except json.JSONDecodeError:
            # Console prefixes outside a frame are not evidence. Inside a frame
            # a malformed line must never be silently discarded.
            if active:
                raise RuntimeError("malformed evidence output")
            end = output.find("\n", offset)
            if end < 0:
                break
            offset = end + 1
            continue
        offset = end
        if isinstance(value, dict):
            values.append(value)
            if value.get("record") in ("stream_header", "diff_header"):
                active = True
            elif value.get("record") == "evidence" and value.get("scope") in ("stream", "comparison"):
                active = False
    result, current, contract = [], [], None
    for value in values:
        declared = value.get("contract")
        record = value.get("record")
        if declared == "anatomist-diff/v2" and "comparison" in value:
            if current:
                raise RuntimeError("unfinished frame before diff document")
            result.append((declared, [value["comparison"], *value["changes"], value["evidence"]]))
            continue
        if record in ("stream_header", "diff_header"):
            if current:
                raise RuntimeError("unfinished evidence frame")
            expected = "semantic-stream/v1" if record == "stream_header" else "anatomist-diff/v2"
            if declared != expected:
                raise RuntimeError("invalid evidence header contract")
            current, contract = [value], declared
            continue
        if not current:
            if record is not None:
                raise RuntimeError("unframed evidence record")
            continue
        if declared is not None and declared != contract:
            raise RuntimeError("mixed evidence contracts")
        current.append(value)
        if record == "evidence":
            scope = "stream" if contract == "semantic-stream/v1" else "comparison"
            if value.get("scope") != scope:
                # Semantic commands can carry nonterminal per-item evidence.
                if contract == "anatomist-diff/v2":
                    raise RuntimeError("invalid diff footer")
                continue
            result.append((contract, current))
            current, contract = [], None
    if current:
        raise RuntimeError("missing final evidence")
    return result


def semantic_frames(output):
    return [rows for contract, rows in frames(output) if contract == "semantic-stream/v1"]


def version_source_frames(output):
    """Version navigation accepts the CLI's complete JSON or NDJSON source envelope."""
    try:
        document = json.loads(output)
    except ValueError:
        return semantic_frames(output)
    if not isinstance(document, dict) or document.get('contract') != 'semantic-stream/v1':
        return semantic_frames(output)
    if not isinstance(document.get('results'), list) or not isinstance(document.get('identity'), dict):
        raise RuntimeError('invalid source JSON envelope')
    identity = document['identity']
    if not all(isinstance(identity.get(key), str) and identity[key] for key in
               ('index_revision_id', 'source_snapshot_id', 'semantic_profile_id')):
        raise RuntimeError('missing source identity')
    evidence = document.get('evidence', {})
    footer = evidence.get('stream') if isinstance(evidence, dict) else None
    if not isinstance(footer, dict) or footer.get('record') != 'evidence' or footer.get('scope') != 'stream':
        raise RuntimeError('missing source stream evidence')
    rows = [{'record': 'stream_header', 'contract': 'semantic-stream/v1', 'identity': identity},
            *document['results'], footer]
    return semantic_frames('\n'.join(json.dumps(row) for row in rows))


def embedded_semantic_frames(output):
    """Complete NDJSON streams among Python labels and separate summaries.

    Each selected frame is still validated strictly; unrelated summary records
    outside it cannot supply its header, body or footer.
    """
    result, pending = [], []
    for line in output.splitlines():
        header = re.search(r'\{\s*"record"\s*:\s*"stream_header"', line)
        if header and not pending:
            line = line[header.start():]
            pending = [line]
        elif pending:
            pending.append(line)
        else:
            continue
        try:
            value = json.loads(line)
        except ValueError:
            continue
        if value.get("record") == "evidence" and value.get("scope") == "stream":
            try:
                result.extend(semantic_frames("\n".join(pending)))
            except RuntimeError:
                pass
            pending = []
    return result


def diff_documents(output):
    from jsonschema import Draft202012Validator
    schema = json.loads((Path(__file__).resolve().parents[1] / "docs/schema/diff-v2.schema.json").read_text())
    validator = Draft202012Validator(schema)
    documents = []
    for contract, rows in frames(output):
        if contract != "anatomist-diff/v2":
            continue
        document = {"contract": contract, "comparison": rows[0], "changes": rows[1:-1], "evidence": rows[-1]}
        validator.validate(document)
        documents.append(document)
    return documents
