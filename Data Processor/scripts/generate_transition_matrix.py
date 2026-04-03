"""
Build a transition-probability matrix from sessionized audit-trail CSV data.

The generated JSON is consumed by `TransitionMatrixService` to flag action
transitions that are statistically rare or effectively impossible.
"""

import pandas as pd
import json
import os


def generate_transition_matrix(csv_path, output_path):
    print(f"Reading data from {csv_path}...")
    df = pd.read_csv(csv_path)

    # Sort events by session and timestamp/sequence
    df['CREATED_AT'] = pd.to_datetime(df['CREATED_AT'])
    df = df.sort_values(by=['SESSION_ID', 'CREATED_AT'])

    # Calculate transitions
    print("Calculating transitions...")
    # Shift action column by -1 to get the next action
    df['NEXT_ACTION'] = df.groupby('SESSION_ID')['ACTION'].shift(-1)

    # Drop rows without a next action
    transitions = df.dropna(subset=['NEXT_ACTION'])

    # Count transitions
    transition_counts = transitions.groupby(['ACTION', 'NEXT_ACTION']).size().reset_index(name='count')

    # Calculate probabilities
    action_totals = transition_counts.groupby('ACTION')['count'].sum().reset_index(name='total')
    transition_probs = pd.merge(transition_counts, action_totals, on='ACTION')
    transition_probs['probability'] = transition_probs['count'] / transition_probs['total']

    # Convert to nested dictionary format required by the Java service
    matrix = {}
    for _, row in transition_probs.iterrows():
        action = row['ACTION']
        next_action = row['NEXT_ACTION']
        prob = row['probability']
        
        if action not in matrix:
            matrix[action] = {}
        matrix[action][next_action] = prob

    # Write to JSON
    print(f"Writing transition matrix to {output_path}...")
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(matrix, f, indent=2, ensure_ascii=False)

    print(f"Successfully generated matrix with {len(matrix)} starting states.")


if __name__ == "__main__":
    # Default paths target the repository's bundled training/export assets.
    csv_file = "src/main/resources/AI/audit_trail_2025.csv"
    output_file = "src/main/resources/AI/transition_matrix.json"
    generate_transition_matrix(csv_file, output_file)
