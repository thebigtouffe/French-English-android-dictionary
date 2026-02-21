#!/usr/bin/env python3
"""
Dictionary XML to SQLite Converter with Simple Prefix Search

This script converts your XML dictionary into an optimized SQLite database
with per-entry GZIP compression and simple prefix search using LIKE.

No FTS needed - just uses standard SQL LIKE for prefix matching.

Usage:
    python build_dictionary_db.py dictionary.xml

Output:
    dictionary.db - Ready to place in app/src/main/assets/
"""

import sqlite3
import gzip
import sys
import os
from pathlib import Path
import xml.etree.ElementTree as ET

def compress_string(text):
    """Compress a string using GZIP"""
    return gzip.compress(text.encode('utf-8'), compresslevel=9)

def create_database(db_path):
    """Create the SQLite database with simple schema"""
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    # Main table for entries
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            word TEXT NOT NULL,
            word_lower TEXT NOT NULL,
            entry_compressed BLOB NOT NULL
        )
    """)
    
    # Index for fast lowercase word lookups and prefix search
    cursor.execute("""
        CREATE INDEX IF NOT EXISTS idx_word_lower ON entries(word_lower)
    """)
    
    conn.commit()
    return conn

def parse_xml_entry(element):
    """Convert XML element to string, preserving all attributes and structure"""
    return ET.tostring(element, encoding='unicode', method='xml')

def import_xml_to_db(xml_file, db_file):
    """Import XML dictionary into SQLite database"""
    
    print(f"Reading XML file: {xml_file}")
    
    # Parse XML
    try:
        tree = ET.parse(xml_file)
        root = tree.getroot()
    except Exception as e:
        print(f"Error parsing XML: {e}")
        return False
    
    # Create database
    print(f"Creating database: {db_file}")
    conn = create_database(db_file)
    cursor = conn.cursor()
    
    # Find all entry elements
    entries = root.findall('.//{http://www.apple.com/DTDs/DictionaryService-1.0.rng}entry')
    if not entries:
        entries = root.findall('.//entry')
    
    if not entries:
        print("Error: No entries found in XML file")
        return False
    
    total_entries = len(entries)
    print(f"Found {total_entries} entries")
    print("Importing entries (this may take a few minutes)...")
    
    # Prepare insert statement
    insert_sql = """
        INSERT INTO entries (word, word_lower, entry_compressed)
        VALUES (?, ?, ?)
    """
    
    # Process entries in batches
    batch_size = 100
    entries_data = []
    
    for i, entry in enumerate(entries):
        # Get the word title
        title = entry.get('{http://www.apple.com/DTDs/DictionaryService-1.0.rng}title')
        if not title:
            title = entry.get('title')
        
        if not title:
            print(f"Warning: Entry {i} has no title, skipping")
            continue
        
        # Convert entry to XML string
        entry_xml = parse_xml_entry(entry)
        
        # Compress the XML
        compressed_xml = compress_string(entry_xml)
        
        # Add to batch
        entries_data.append((title, title.lower(), compressed_xml))
        
        # Insert batch
        if len(entries_data) >= batch_size:
            cursor.executemany(insert_sql, entries_data)
            conn.commit()
            entries_data = []
        
        # Progress
        if (i + 1) % 500 == 0:
            percent = ((i + 1) / total_entries) * 100
            print(f"  Progress: {i + 1}/{total_entries} ({percent:.1f}%) - {title}")
    
    # Insert remaining entries
    if entries_data:
        cursor.executemany(insert_sql, entries_data)
        conn.commit()
    
    print(f"\nImported {total_entries} entries")
    
    # Optimize database
    print("Optimizing database...")
    cursor.execute("VACUUM")
    cursor.execute("ANALYZE")
    conn.commit()
    
    # Get statistics
    cursor.execute("SELECT COUNT(*) FROM entries")
    entry_count = cursor.fetchone()[0]
    
    cursor.execute("SELECT SUM(LENGTH(entry_compressed)) FROM entries")
    total_compressed_size = cursor.fetchone()[0]
    
    db_size = os.path.getsize(db_file)
    
    print("\n" + "="*60)
    print("DATABASE STATISTICS")
    print("="*60)
    print(f"Total entries:           {entry_count:,}")
    print(f"Compressed data size:    {total_compressed_size / (1024*1024):.2f} MB")
    print(f"Database file size:      {db_size / (1024*1024):.2f} MB")
    print(f"Average entry size:      {total_compressed_size / entry_count / 1024:.2f} KB (compressed)")
    print("="*60)
    
    conn.close()
    
    print(f"\n✓ Database created successfully: {db_file}")
    print(f"\nNext steps:")
    print(f"1. Copy the database to your Android project:")
    print(f"   cp {db_file} app/src/main/assets/")
    print(f"2. Build and run your app!")
    
    return True

def test_database(db_file, test_word=None):
    """Test the created database"""
    print(f"\nTesting database: {db_file}")
    
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()
    
    # Get total entries
    cursor.execute("SELECT COUNT(*) FROM entries")
    total = cursor.fetchone()[0]
    print(f"Total entries: {total:,}")
    
    # Test prefix search
    if test_word:
        # Exact match
        cursor.execute("SELECT word, entry_compressed FROM entries WHERE word_lower = ?", 
                      (test_word.lower(),))
        result = cursor.fetchone()
        if result:
            word, compressed = result
            decompressed = gzip.decompress(compressed).decode('utf-8')
            print(f"\nExact match for '{test_word}':")
            print(f"  Found: {word}")
            print(f"  Compressed size: {len(compressed)} bytes")
            print(f"  Decompressed size: {len(decompressed)} bytes")
            print(f"  Compression ratio: {(1 - len(compressed)/len(decompressed))*100:.1f}%")
        
        # Prefix search
        prefix = test_word[:3] if len(test_word) >= 3 else test_word
        cursor.execute("""
            SELECT word 
            FROM entries 
            WHERE word_lower LIKE ? 
            ORDER BY word_lower 
            LIMIT 10
        """, (f"{prefix.lower()}%",))
        
        words = [row[0] for row in cursor.fetchall()]
        print(f"\nPrefix search for '{prefix}*': {len(words)} results")
        print(f"  Sample: {', '.join(words[:5])}")
    else:
        # Get first word
        cursor.execute("SELECT word FROM entries ORDER BY word LIMIT 1")
        first_word = cursor.fetchone()[0]
        print(f"First word: {first_word}")
    
    conn.close()

def main():
    if len(sys.argv) < 2:
        print("Usage: python build_dictionary_db.py <input_xml_file> [--test <word>]")
        print("\nExample:")
        print("  python build_dictionary_db.py dictionary.xml")
        print("  python build_dictionary_db.py dictionary.xml --test hello")
        sys.exit(1)
    
    input_file = sys.argv[1]
    input_path = Path(input_file)
    
    if not input_path.exists():
        print(f"Error: File '{input_file}' not found")
        sys.exit(1)
    
    # Determine output filename
    output_file = input_path.stem + '.db'
    
    # Check if already exists
    if os.path.exists(output_file):
        response = input(f"\n{output_file} already exists. Overwrite? (y/n): ")
        if response.lower() != 'y':
            print("Cancelled")
            sys.exit(0)
        os.remove(output_file)
    
    # Import
    success = import_xml_to_db(input_file, output_file)
    
    if not success:
        sys.exit(1)
    
    # Test
    test_word = None
    if '--test' in sys.argv:
        test_idx = sys.argv.index('--test')
        if test_idx + 1 < len(sys.argv):
            test_word = sys.argv[test_idx + 1]
    
    test_database(output_file, test_word)
    
    print("\n✓ All done!")

if __name__ == '__main__':
    main()
