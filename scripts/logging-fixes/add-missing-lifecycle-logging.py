#!/usr/bin/env python3
"""
Add missing setUp/tearDown logger.info lines to files that have LoggerFactory.getLogger
and manager.start().await() but are missing the lifecycle logging.
"""

import re
import os

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    content = ''.join(lines)
    if 'LoggerFactory.getLogger' not in content or 'manager.start().await()' not in content:
        return False
    
    changes = 0
    new_lines = []
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        
        # Detect @BeforeEach → setUp
        if stripped == '@BeforeEach':
            new_lines.append(line)
            i += 1
            # Find setUp method signature
            while i < len(lines):
                l = lines[i]
                s = l.strip()
                if re.match(r'void\s+setUp\s*\(', s):
                    if '{' in s:
                        new_lines.append(l)
                        i += 1
                        # Check if next non-blank line is already the logger.info
                        if i < len(lines) and 'logger.info("Setting up:' in lines[i]:
                            pass  # Already there
                        else:
                            new_lines.append('        logger.info("Setting up: configuring database and starting PeeGeeQManager");\n')
                            changes += 1
                    else:
                        new_lines.append(l)
                        i += 1
                        # Find opening brace
                        while i < len(lines) and '{' not in lines[i]:
                            new_lines.append(lines[i])
                            i += 1
                        if i < len(lines):
                            new_lines.append(lines[i])
                            i += 1
                            if i < len(lines) and 'logger.info("Setting up:' in lines[i]:
                                pass
                            else:
                                new_lines.append('        logger.info("Setting up: configuring database and starting PeeGeeQManager");\n')
                                changes += 1
                    break
                else:
                    new_lines.append(l)
                    i += 1
            continue
        
        # Detect @AfterEach → tearDown
        if stripped == '@AfterEach':
            new_lines.append(line)
            i += 1
            while i < len(lines):
                l = lines[i]
                s = l.strip()
                if re.match(r'void\s+tearDown\s*\(', s):
                    if '{' in s:
                        new_lines.append(l)
                        i += 1
                        if i < len(lines) and 'logger.info("Tearing down:' in lines[i]:
                            pass
                        else:
                            new_lines.append('        logger.info("Tearing down: closing resources and manager");\n')
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
                                new_lines.append('        logger.info("Tearing down: closing resources and manager");\n')
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
            f.writelines(new_lines)
        print(f"  ADDED {changes} missing lifecycle log(s): {os.path.basename(filepath)}")
        return True
    return False

if __name__ == '__main__':
    base = r'c:\Users\mraysmit\dev\idea-projects\peegeeq'
    modules = ['peegeeq-native', 'peegeeq-outbox', 'peegeeq-examples', 'peegeeq-examples-spring', 'peegeeq-db']
    
    fixed = 0
    for module in modules:
        test_dir = os.path.join(base, module, 'src', 'test', 'java')
        if not os.path.isdir(test_dir):
            continue
        print(f"\n{module}:")
        for dirpath, _, filenames in os.walk(test_dir):
            for fname in sorted(filenames):
                if fname.endswith('.java'):
                    fpath = os.path.join(dirpath, fname)
                    if process_file(fpath):
                        fixed += 1
    print(f"\nDone. Added missing lifecycle logging to {fixed} files.")
