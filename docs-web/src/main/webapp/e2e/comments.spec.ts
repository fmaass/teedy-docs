import { test, expect } from './fixtures'
import { unique, createDocument, confirmDanger } from './helpers'

// Add and delete a comment on a document (the Comments tab of the document view).

test('add and delete a comment on a document', async ({ page }) => {
  const title = unique('cmt')
  const { id } = await createDocument(page, title)
  const commentText = `e2e comment ${Date.now()}`

  await page.goto(`/#/document/view/${id}/comments`)
  // The comment textarea is labelled "Add a comment".
  const box = page.getByLabel('Add a comment')
  await expect(box).toBeVisible()
  await box.fill(commentText)
  await page.getByRole('button', { name: 'Post comment' }).click()

  // The new comment renders in the list.
  const comment = page.locator('.comment-item', { hasText: commentText })
  await expect(comment).toBeVisible()

  // Delete it via the per-comment trash button + confirm.
  await comment.getByRole('button', { name: 'Delete' }).click()
  await confirmDanger(page)
  await expect(page.locator('.comment-item', { hasText: commentText })).toHaveCount(0)

  // Cleanup the document.
  await page.goto(`/#/document/view/${id}`)
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await confirmDanger(page)
})
