#!/usr/bin/env python3
"""
Final fix: add setUp/tearDown logging to files that use setup() (lowercase)
or other method name variants. Also add Logger import+field where missing.
"""
import re
import os

FILES = [
    r"peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerCoverageTest.java",
    r"peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerEdgeCasesCoverageTest.java",
    r"peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerErrorPathsCoverageTest.java",
    r"peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxConsumerSurgicalCoverageTest.java",
    r"peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxProducerAdditionalCoverageTest.java",
    r"peegeeq-outbox\src\test\java\dev\mars\peegeeq\outbox\OutboxSchemaQuotingTest.java",
    r"peegeeq-examples\src\test\java\dev\mars\peegeeq\examples\bitemporal\CloudEventsJsonbQueryTest.java",
    r"peegeeq-examples\src\test\java\dev\mars\peegeeq\examples\outbox\MultiConfigurationIntegrationTest.java",
    r"peegeeq-examples\src\test\java\dev\mars\peegeeq\examples\patterns\PeeGeeQExampleTest.java",
]

BASE = r'c:\Users\mraysmit\dev\idea-projects\peegeeq'

# Setup method name variants (case-insensitive match)
SETUP_RE = re.compile(r'void\s+(setUp|setup|setUpStreams|setupAll|init)\s*\(', re.IGNORECASE)
TEARDOWN_RE = re.compile(r'void\s+(tearDown|teardown)\s*\(', re.IGNORECASE)

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    lines = content.split('\n')
    
    # Extract class name
    class_match = re.search(r'class\s+(\w+)', content)
    if not class_match:
        return False
    class_name = class_match.group(1)
    
    needs_import = 'LoggerFactory.getLogger' not in content
    
    new_lines = []
    changes = 0
    added_imports = False
    added_field = False
    
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        
        # Add imports if needed (after last import line)
        if needs_import and not added_imports and stripped.startswith('import ') and not stripped.startswith('import org.slf4j'):
            # Find last import
            j = i
            last_import = i
            while j < len(lines) - 1:
                next_s = lines[j + 1].strip()
                if next_s.startswith('import '):
                    last_import = j + 1
                j += 1
                if not (next_s.startswith('import ') or next_s == ''):
                    break
            # Add lines up to last import
            while i <= last_import:
                new_lines.append(lines[i])
                i += 1
            new_lines.append('import org.slf4j.Logger;')
            new_lines.append('import org.slf4j.LoggerFactory;')
            added_imports = True
            changes += 1
            continue
        
        # Add logger field after class declaration
        if needs_import and not added_field and re.search(r'class\s+' + re.escape(class_name) + r'\b', stripped):
            new_lines.append(line)
            if '{' in line:
                new_lines.append(f'    private static final Logger logger = LoggerFactory.getLogger({class_name}.class);')
                new_lines.append('')
                added_field = True
                changes += 1
            i += 1
            continue
        
        # Detect @BeforeEach or @BeforeAll
        if stripped in ('@BeforeEach', '@BeforeAll'):
            new_lines.append(line)
            i += 1
            # Find setup method
            while i < len(lines):
                l = lines[i]
                s = l.strip()
                m = SETUP_RE.match(s)
                if m:
                    if '{' in s:
                        new_lines.append(l)
                        i += 1
                        # Check if next line already has our logger
                        if i < len(lines) and 'logger.info("Setting up:' in lines[i]:
                            pass  # Already there
                        else:
                            new_lines.append('        logger.info("Setting up: configuring database and starting PeeGeeQManager");')
                            changes += 1
                    else:
                        new_lines.append(l)
                        i += 1
                        while i < len(lines) and '{' not in lines[i]:
                            new_lines.append(lines[i])
                            i += 1
                        if i < len(lines):
                            new_lines.append(lines[i])
                            i += 1
                            if i < len(lines) and 'logger.info("Setting up:' in lines[i]:
                                pass
                            else:
                                new_lines.append('        logger.info("Setting up: configuring database and starting PeeGeeQManager");')
                                changes += 1
                    break
                else:
                    new_lines.append(l)
                    i += 1
            continue
        
        # Detect @AfterEach
        if stripped == '@AfterEach':
            new_lines.append(line)
            i += 1
            while i < len(lines):
                l = lines[i]
                s = l.strip()
                m = TEARDOWN_RE.match(s)
                if m:
                    if '{' in s:
                        new_lines.append(l)
                        i += 1
                        if i < len(lines) and 'logger.info("Tearing down:' in lines[i]:
                            pass
                        else:
                            new_lines.append('        logger.info("Tearing down: closing resources and manager");')
                            changes += 1
                    else:
                        new_lines.append(l)
                        i += 1
                        while i < len(lines) and '{' not in lines[i]:
                            new_lines.append(lines[i])
                            i += 1
                        if i < len(lines):
                            new_lines.append(lines[i])
                            i += 1
                            if i < len(lines) and 'logger.info("Tearing down:' in lines[i]:
                                pass
                            else:
                                new_lines.append('        logger.info("Tearing down: closing resources and manager");')
                                changes += 1
                    break
                else:
                    new_lines.append(l)
                    i += 1
            continue
        
        new_lines.append(line)
        i += 1
    
    if changes > 0:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write('\n'.join(new_lines))
        print(f"  FIXED ({changes} changes): {os.path.basename(filepath)}")
        return True
    else:
        print(f"  SKIP (no changes needed): {os.path.basename(filepath)}")
        return False

if __name__ == '__main__':
    fixed = 0
    for relpath in FILES:
        fpath = os.path.join(BASE, relpath)
        if not os.path.isfile(fpath):
            print(f"  NOT FOUND: {relpath}")
            continue
        if process_file(fpath):
            fixed += 1
    print(f"\nDone. Fixed {fixed} files.")
