There are some issues in the demo server.

# Chapter 1
`Active Stock Alerts` on Page 3 is always showing the same before and after, since we are waiting on page 2 to see the updated values. Either show the current table or find some way to capture the before state prior to the flow.
The easy option would be to merge page 2 and 3. show the Active Stock Alerts in page 2 itself. Page 3 becomes redundant

# Chapter 2
1. Page 2 `Purchase Orders` also show the same before and after.
2. Page 3 'Verify FLow 3` fails since Scenaio 2a. is marked for auto approval but in reality it is waiting for approval the reverse for 2b I assume.
3. Maybe add some tables in this page 3 to show the before after if possible

# Chapter 3
Page 2: which row to Approve to complete the flow?  
Page 3: PO STATUS after APPROVAL will have the same before an after view in the tables, unless you find a way to capture the before state prior to the flow.

# Chapter 4:
Merge page 1 and 2. No tables, just the iframe view of the portal

# Chapter 5
    Merge page 1 and 2. No tables or the evidence checklist, just the iframe view of the portal

# Chapter 6: remove completely


