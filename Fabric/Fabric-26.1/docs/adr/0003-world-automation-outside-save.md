# World automation is stored outside the save

Current-world automation is keyed by the world's normalized location but stored under the MineBackup config directory. Keeping the plan outside the save prevents a restore from silently reverting, re-enabling, or disabling backup automation; renaming a world deliberately gives it a new disabled plan rather than guessing that two paths represent the same world.
